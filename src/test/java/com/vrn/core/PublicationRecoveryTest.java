package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class PublicationRecoveryTest {

    @TempDir
    Path tempDir;

    private Path storage;
    private Path index;
    private Path head;
    private Path pending;
    private String hash;

    @BeforeEach
    void setUp() throws Exception {
        storage = tempDir.resolve("manual-storage");
        Files.createDirectories(storage.resolve("objects"));
        Files.createDirectories(storage.resolve("snapshots"));
        Files.createDirectories(storage.resolve("staging"));
        head = storage.resolve("HEAD");
        index = storage.resolve("staging/index");
        pending = storage.resolve("staging/commit.pending");
        Files.writeString(head, "NONE");
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "Actual stored content");
        FileHasher.HashedFile content = new FileHasher().hashWithBytes(source);
        hash = content.hash();
        new ObjectStore(storage).store(hash, content.bytes());
        new StagingArea(storage).stage(Path.of("old.txt"), hash);
    }

    @Test
    void stagingReplacement_publishesCompleteIndexAndIgnoresOtherTemporaryFiles() throws Exception {
        String oldIndex = Files.readString(index);
        Path abandoned = index.resolveSibling(".index-abandoned.tmp");
        Files.writeString(abandoned, "incomplete temporary content");
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                assertEquals(oldIndex, Files.readString(index));
                assertEquals(index.getParent(), temporary.getParent());
                assertEquals(Map.of("old.txt", hash, "a=b.txt", hash),
                        MetadataCodec.decodeIndex(Files.readAllLines(temporary)));
                super.move(temporary, destination);
            }
        };

        new StagingArea(storage, publisher).stage(Path.of("a=b.txt"), hash);

        assertEquals(Map.of("old.txt", hash, "a=b.txt", hash), new StagingArea(storage).readAll());
        assertTrue(Files.exists(abandoned));
    }

    @Test
    void interruptedTemporaryWrite_preservesPublishedStaging() throws Exception {
        String before = Files.readString(index);
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void write(Path temporary, byte[] content) throws IOException {
                Files.writeString(temporary, "partial write");
                throw new IOException("injected temporary write failure");
            }
        };

        assertThrows(IOException.class,
                () -> new StagingArea(storage, publisher).stage(Path.of("new.txt"), hash));

        assertEquals(before, Files.readString(index));
        assertEquals(Map.of("old.txt", hash), new StagingArea(storage).readAll());
        assertNoTemporaries(index.getParent());
    }

    @Test
    void failedStagingRename_preservesPublishedStaging() throws Exception {
        String before = Files.readString(index);

        assertThrows(IOException.class, () -> new StagingArea(storage, failMove(p -> p.equals(index)))
                .stage(Path.of("new.txt"), hash));

        assertEquals(before, Files.readString(index));
        assertNoTemporaries(index.getParent());
    }

    @Test
    void unsupportedAtomicMove_failsWithoutAnOrdinaryMoveFallback() throws Exception {
        String before = Files.readString(index);
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                throw new AtomicMoveNotSupportedException(temporary.toString(), destination.toString(), "injected");
            }
        };

        assertThrows(AtomicMoveNotSupportedException.class,
                () -> new StagingArea(storage, publisher).stage(Path.of("new.txt"), hash));

        assertEquals(before, Files.readString(index));
        assertNoTemporaries(index.getParent());
    }

    @Test
    void successfulCommit_publishesManifestBeforeHeadAndCleansConsumedStaging() throws Exception {
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                if (destination.equals(head)) {
                    String id = Files.readString(temporary);
                    assertTrue(Files.exists(storage.resolve("snapshots/" + id + ".snapshot")));
                    assertEquals("NONE", Files.readString(head));
                    assertTrue(Files.exists(index));
                    assertTrue(Files.exists(pending));
                }
                super.move(temporary, destination);
            }
        };

        CommitService.Result result = new CommitService(storage, publisher).commit("publish");

        assertEquals(result.snapshotId(), Files.readString(head));
        assertEquals(Map.of("old.txt", hash), new SnapshotStore(storage).load(result.snapshotId()).files());
        assertEquals("NONE", new SnapshotStore(storage).load(result.snapshotId()).parentId());
        assertNull(result.warning());
        assertFalse(Files.exists(index));
        assertFalse(Files.exists(pending));
    }

    @Test
    void snapshotPublicationFailure_preservesPreviousHeadAndStaging() throws Exception {
        String parent = committedParentAndFreshStaging();
        String before = Files.readString(index);

        assertThrows(IOException.class, () -> new CommitService(storage,
                failMove(p -> p.getFileName().toString().endsWith(".snapshot"))).commit("fails"));

        assertEquals(parent, Files.readString(head));
        assertEquals(before, Files.readString(index));
        assertEquals(Map.of("next.txt", hash), new StagingArea(storage).readAll());
        assertEquals(1, snapshotCount());
        assertFalse(Files.exists(pending));
        assertNoTemporaries(storage.resolve("snapshots"));
    }

    @Test
    void recoveryRecordPublicationFailure_doesNotAdvanceHead() throws Exception {
        String before = Files.readString(index);

        assertThrows(IOException.class, () -> new CommitService(storage,
                failMove(p -> p.equals(pending))).commit("fails before HEAD"));

        assertEquals("NONE", Files.readString(head));
        assertEquals(before, Files.readString(index));
        assertFalse(Files.exists(pending));
        assertEquals(1, snapshotCount()); // Stored candidate is not an active commit.
        assertNoTemporaries(index.getParent());
    }

    @Test
    void headPublicationFailure_preservesStagingAndAllowsRetryFromTheOldParent() throws Exception {
        String parent = committedParentAndFreshStaging();
        String before = Files.readString(index);

        assertThrows(IOException.class,
                () -> new CommitService(storage, failMove(p -> p.equals(head))).commit("HEAD fails"));

        assertEquals(parent, Files.readString(head));
        assertEquals(before, Files.readString(index));
        assertEquals(Map.of("next.txt", hash), new StagingArea(storage).readAll());
        assertEquals(2, snapshotCount());
        assertTrue(Files.exists(pending));
        assertNoTemporaries(storage);

        new StagingArea(storage).recover();
        assertFalse(Files.exists(pending));
        assertEquals(before, Files.readString(index));
        CommitService.Result retry = new CommitService(storage).commit("retry");
        assertEquals(parent, new SnapshotStore(storage).load(retry.snapshotId()).parentId());
        assertEquals(retry.snapshotId(), Files.readString(head));
    }

    @Test
    void cleanupFailure_returnsPublishedResultAndHidesConsumedEntriesUntilRecovery() throws Exception {
        CommitService.Result result = new CommitService(storage, failDelete(index)).commit("committed");

        assertEquals(result.snapshotId(), Files.readString(head));
        assertNotNull(result.warning());
        assertTrue(result.warning().contains("Snapshot is active"));
        assertTrue(Files.exists(index));
        assertTrue(Files.exists(pending));
        assertTrue(new StagingArea(storage).readAll().isEmpty());

        new StagingArea(storage).recover();
        assertFalse(Files.exists(index));
        assertFalse(Files.exists(pending));
        assertEquals(result.snapshotId(), Files.readString(head));
        assertThrows(IllegalStateException.class, () -> new CommitService(storage).commit("no stale commit"));
        assertEquals(1, snapshotCount());
    }

    @Test
    void headRenameErrorAfterPublication_isReconciledAsAnActiveCommit() throws Exception {
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                super.move(temporary, destination);
                if (destination.equals(head)) {
                    throw new IOException("injected error after rename took effect");
                }
            }
        };

        CommitService.Result result = new CommitService(storage, publisher).commit("active despite I/O error");

        assertEquals(result.snapshotId(), Files.readString(head));
        assertNotNull(result.warning());
        assertTrue(result.warning().contains("Snapshot is active"));
        assertTrue(new StagingArea(storage).readAll().isEmpty());
        new StagingArea(storage).recover();
        assertFalse(Files.exists(pending));
    }

    @Test
    void unreadableHeadAfterRename_reportsAnUncertainOutcomeAndPreservesRecoveryData() throws Exception {
        Path unavailableHead = storage.resolve("HEAD.unavailable");
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                super.move(temporary, destination);
                if (destination.equals(head)) {
                    Files.move(head, unavailableHead); // Deterministic loss of access to the pointer.
                    throw new IOException("injected pointer access failure");
                }
            }
        };

        IOException failure = assertThrows(IOException.class,
                () -> new CommitService(storage, publisher).commit("uncertain outcome"));

        assertTrue(failure.getMessage().contains("Commit outcome is uncertain"));
        assertTrue(Files.exists(index));
        assertTrue(Files.exists(pending));
        Files.move(unavailableHead, head);
        assertTrue(new StagingArea(storage).readAll().isEmpty());
        new StagingArea(storage).recover();
        assertFalse(Files.exists(pending));
    }

    @Test
    void newerStaging_survivesSubsequentCleanupRetries() throws Exception {
        CommitService.Result result = new CommitService(storage, failDelete(index)).commit("committed");

        new StagingArea(storage).stage(Path.of("newer.txt"), hash);
        new StagingArea(storage).recover();
        new StagingArea(storage).recover();

        assertEquals(Map.of("newer.txt", hash), new StagingArea(storage).readAll());
        assertEquals(result.snapshotId(), Files.readString(head));
        assertFalse(Files.exists(pending));
    }

    @Test
    void identicalRestaging_isNewWorkAfterTheRecoveryBarrierIsRetired() throws Exception {
        String identicalIndex = Files.readString(index);
        CommitService.Result result = new CommitService(storage, failDelete(pending)).commit("committed");
        assertNotNull(result.warning());
        assertFalse(Files.exists(index));
        assertTrue(Files.exists(pending));

        new StagingArea(storage).stage(Path.of("old.txt"), hash);
        assertEquals(identicalIndex, Files.readString(index));
        new StagingArea(storage).recover();

        assertEquals(Map.of("old.txt", hash), new StagingArea(storage).readAll());
        assertFalse(Files.exists(pending));
        CommitService.Result next = new CommitService(storage).commit("explicitly restaged");
        assertEquals(result.snapshotId(), new SnapshotStore(storage).load(next.snapshotId()).parentId());
    }

    @Test
    void persistentCleanupFailure_blocksNewStagingRatherThanMixingGenerations() throws Exception {
        AtomicFilePublisher failing = failDelete(index);
        new CommitService(storage, failing).commit("active");
        String consumed = Files.readString(index);

        assertThrows(IOException.class,
                () -> new StagingArea(storage, failing).stage(Path.of("newer.txt"), hash));

        assertEquals(consumed, Files.readString(index));
        assertTrue(new StagingArea(storage).readAll().isEmpty());
    }

    @Test
    void uncoordinatedIndexChange_isPreservedAndRecoveryFailsClosed() throws Exception {
        CommitService.Result result = new CommitService(storage, failDelete(index)).commit("active");
        String changed = MetadataCodec.encodeIndex(Map.of("external-newer.txt", hash));
        Files.writeString(index, changed); // Simulates a writer that ignores the protocol.

        IOException failure = assertThrows(IOException.class, () -> new StagingArea(storage).recover());

        assertTrue(failure.getMessage().contains("refusing to clear"));
        assertEquals(changed, Files.readString(index));
        assertEquals(result.snapshotId(), Files.readString(head));
        assertTrue(Files.exists(pending));
        assertThrows(IOException.class, () -> new StagingArea(storage).readAll());
    }

    @Test
    void interruptionImmediatelyAfterHeadRename_isRecognizedOnRestart() throws Exception {
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                super.move(temporary, destination);
                if (destination.equals(head)) {
                    throw new SimulatedInterruption();
                }
            }
        };

        assertThrows(SimulatedInterruption.class, () -> new CommitService(storage, publisher).commit("interrupted"));
        String active = Files.readString(head);
        assertNotEquals("NONE", active);
        assertEquals(active, new SnapshotStore(storage).load(active).id());
        assertTrue(Files.exists(index));
        assertTrue(new StagingArea(storage).readAll().isEmpty());

        new StagingArea(storage).stage(Path.of("newer.txt"), hash);
        assertEquals(Map.of("newer.txt", hash), new StagingArea(storage).readAll());
        assertEquals(active, Files.readString(head));
    }

    @Test
    void interruptionBeforeHeadRename_leavesStagingPending() throws Exception {
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                if (destination.equals(head)) {
                    throw new SimulatedInterruption();
                }
                super.move(temporary, destination);
            }
        };

        assertThrows(SimulatedInterruption.class, () -> new CommitService(storage, publisher).commit("interrupted"));

        assertEquals("NONE", Files.readString(head));
        assertEquals(Map.of("old.txt", hash), new StagingArea(storage).readAll());
        new StagingArea(storage).recover();
        assertFalse(Files.exists(pending));
        assertEquals(Map.of("old.txt", hash), new StagingArea(storage).readAll());
    }

    @Test
    void invalidRecoveryState_preservesStagingAndBlocksMutation() throws Exception {
        String before = Files.readString(index);
        Files.writeString(pending, "invalid pending record");

        assertThrows(IOException.class, () -> new StagingArea(storage).stage(Path.of("newer.txt"), hash));
        assertThrows(IOException.class, () -> new CommitService(storage).commit("blocked"));

        assertEquals(before, Files.readString(index));
        assertEquals("NONE", Files.readString(head));
    }

    @Test
    void unexpectedHeadDuringRecovery_doesNotClearStaging() throws Exception {
        assertThrows(IOException.class,
                () -> new CommitService(storage, failMove(p -> p.equals(head))).commit("unpublished"));
        String before = Files.readString(index);
        Files.writeString(head, "externally-changed-head");

        IOException failure = assertThrows(IOException.class, () -> new StagingArea(storage).recover());

        assertTrue(failure.getMessage().contains("unexpected HEAD"));
        assertEquals(before, Files.readString(index));
        assertEquals("externally-changed-head", Files.readString(head));
        assertTrue(Files.exists(pending));
    }

    @Test
    void publishedManifest_cannotBeOverwrittenThroughTheStore() throws Exception {
        CommitService.Result result = new CommitService(storage).commit("immutable publication");
        Path manifest = storage.resolve("snapshots/" + result.snapshotId() + ".snapshot");
        String original = Files.readString(manifest);

        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> new SnapshotStore(storage)
                .save(new Snapshot(result.snapshotId(), "NONE", 0, "replacement", Map.of())));

        assertEquals(original, Files.readString(manifest));
    }

    private String committedParentAndFreshStaging() throws Exception {
        String parent = new CommitService(storage).commit("parent").snapshotId();
        new StagingArea(storage).stage(Path.of("next.txt"), hash);
        return parent;
    }

    private AtomicFilePublisher failMove(Predicate<Path> target) {
        return new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                if (target.test(destination)) {
                    throw new IOException("injected publication failure: " + destination.getFileName());
                }
                super.move(temporary, destination);
            }
        };
    }

    private AtomicFilePublisher failDelete(Path target) {
        return new AtomicFilePublisher() {
            @Override
            public void delete(Path path) throws IOException {
                if (path.equals(target)) {
                    throw new IOException("injected cleanup failure: " + target.getFileName());
                }
                super.delete(path);
            }
        };
    }

    private long snapshotCount() throws IOException {
        try (var paths = Files.list(storage.resolve("snapshots"))) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".snapshot")).count();
        }
    }

    private void assertNoTemporaries(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            assertEquals(List.of(), paths.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList());
        }
    }

    private static class SimulatedInterruption extends Error {
    }
}
