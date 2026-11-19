package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StagingIntentRecoveryTest {
    @TempDir Path root;
    Path storage;
    Path index;
    Path pending;
    String parent;
    StagingArea staging;

    @BeforeEach void prepare() throws Exception {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        index = storage.resolve("staging/index");
        pending = storage.resolve("staging/commit.pending");
        staging = new StagingArea(storage);
        Files.writeString(root.resolve("old.txt"), "old");
        Files.writeString(root.resolve("keep.txt"), "keep");
        for (String path : new String[]{"old.txt", "keep.txt", ".vrnignore"}) {
            var file = new FileHasher().hashWithBytes(root.resolve(path));
            new ObjectStore(storage).store(file.hash(), file.bytes());
            staging.stage(Path.of(path), file.hash());
        }
        parent = new CommitService(storage).commit("parent").snapshotId();
        Files.delete(root.resolve("old.txt"));
        staging.stageDelete(Path.of("old.txt"));
    }

    @ParameterizedTest @ValueSource(strings = {"snapshot", "pending", "head"})
    void prePublicationFailureRetainsTypedDeletionAndCanRetry(String boundary) throws Exception {
        byte[] before = Files.readAllBytes(index);
        AtomicFilePublisher fail = new AtomicFilePublisher() {
            @Override protected void move(Path temporary, Path destination) throws IOException {
                if ((boundary.equals("snapshot") && destination.toString().endsWith(".snapshot"))
                        || (boundary.equals("pending") && destination.equals(pending))
                        || (boundary.equals("head") && destination.equals(storage.resolve("HEAD")))) {
                    throw new IOException("injected " + boundary + " failure");
                }
                super.move(temporary, destination);
            }
        };
        assertThrows(IOException.class, () -> new CommitService(storage, fail).commit("failure"));
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        assertArrayEquals(before, Files.readAllBytes(index));
        assertEquals(Map.of("old.txt", new StagedOperation.Delete()), staging.readOperations());
        String next = new CommitService(storage).commit("retry").snapshotId();
        assertEquals(parent, new SnapshotStore(storage).load(next).parentId());
        assertFalse(new SnapshotStore(storage).load(next).files().containsKey("old.txt"));
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @ParameterizedTest @ValueSource(strings = {"index", "pending"})
    void cleanupFailureHidesConsumedDeleteAndPreservesLaterSelections(String boundary) throws Exception {
        var result = new CommitService(storage, failDelete(boundary.equals("index") ? index : pending)).commit("published");
        assertNotNull(result.warning());
        assertEquals(result.snapshotId(), Files.readString(storage.resolve("HEAD")));
        assertTrue(staging.readOperations().isEmpty());
        assertTrue(WorkingTreeStatus.compute(root).isClean());
        staging.stageDelete(Path.of("keep.txt"));
        assertFalse(Files.exists(pending));
        assertEquals(Map.of("keep.txt", new StagedOperation.Delete()), staging.readOperations());
        staging.recover();
        assertEquals(Map.of("keep.txt", new StagedOperation.Delete()), staging.readOperations());
        assertEquals("keep", Files.readString(root.resolve("keep.txt")));
    }

    @ParameterizedTest @ValueSource(strings = {"delete", "unstage"})
    void blockedRecoveryPreventsEitherNewMutationFromOverwritingConsumedIntent(String command) throws Exception {
        AtomicFilePublisher blocked = failDelete(index);
        assertNotNull(new CommitService(storage, blocked).commit("published").warning());
        byte[] before = Files.readAllBytes(index);
        StagingArea mutation = new StagingArea(storage, blocked);
        assertThrows(IOException.class, () -> {
            if (command.equals("delete")) mutation.stageDelete(Path.of("keep.txt"));
            else mutation.unstage(Path.of("old.txt"));
        });
        assertArrayEquals(before, Files.readAllBytes(index));
        assertTrue(Files.exists(pending));
        assertTrue(staging.readOperations().isEmpty());
    }

    @Test void changedTypedIndexFingerprintBlocksCleanupWithoutClearingNewerBytes() throws Exception {
        assertNotNull(new CommitService(storage, failDelete(index)).commit("published").warning());
        String newer = MetadataCodec.encodeOperations(Map.of("keep.txt", new StagedOperation.Delete()));
        Files.writeString(index, newer);
        assertThrows(IOException.class, staging::recover);
        assertThrows(IOException.class, staging::readOperations);
        assertThrows(IOException.class, () -> staging.stageDelete(Path.of("keep.txt")));
        assertThrows(IOException.class, () -> staging.unstage(Path.of("keep.txt")));
        assertEquals(newer, Files.readString(index));
        assertTrue(Files.exists(pending));
    }

    @Test void interruptionAfterHeadLeavesARecoverableConsumedVersionTwoIndex() throws Exception {
        AtomicFilePublisher interrupt = new AtomicFilePublisher() {
            @Override protected void move(Path temporary, Path destination) throws IOException {
                super.move(temporary, destination);
                if (destination.equals(storage.resolve("HEAD"))) throw new SimulatedInterruption();
            }
        };
        assertThrows(SimulatedInterruption.class, () -> new CommitService(storage, interrupt).commit("interrupted"));
        assertNotEquals(parent, Files.readString(storage.resolve("HEAD")));
        assertTrue(Files.readString(index).startsWith("VRN-STAGING\t2\n"));
        assertTrue(staging.readOperations().isEmpty());
        assertTrue(WorkingTreeStatus.compute(root).isClean());
        staging.recover();
        assertFalse(Files.exists(index));
        assertFalse(Files.exists(pending));
    }

    @Test void failedMutationAfterRecoveryMayRetireOldHousekeepingButNeverRecreatesDeletedIntent() throws Exception {
        assertNotNull(new CommitService(storage, failDelete(index)).commit("published").warning());
        assertThrows(IllegalArgumentException.class, () -> staging.stageDelete(Path.of("old.txt")));
        assertFalse(Files.exists(index));
        assertFalse(Files.exists(pending));
        assertTrue(staging.readOperations().isEmpty());
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    private static AtomicFilePublisher failDelete(Path target) {
        return new AtomicFilePublisher() {
            @Override public void delete(Path path) throws IOException {
                if (path.equals(target)) throw new IOException("injected cleanup failure");
                super.delete(path);
            }
        };
    }

    private static class SimulatedInterruption extends Error {}
}
