package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StagingIntentTest {
    @TempDir Path root;
    Path storage;
    StagingArea staging;
    String parent;
    String oldHash;
    String keepHash;

    @BeforeEach void initialize() throws Exception {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        staging = new StagingArea(storage);
        oldHash = put("old.txt", "old");
        keepHash = put("keep.txt", "keep");
        var ignore = new FileHasher().hashWithBytes(root.resolve(".vrnignore"));
        new ObjectStore(storage).store(ignore.hash(), ignore.bytes());
        staging.stage(Path.of(".vrnignore"), ignore.hash());
        parent = new CommitService(storage).commit("parent").snapshotId();
    }

    @ParameterizedTest @ValueSource(strings = {"config=production.txt", "#notes.txt", "src/नमस्ते/文件.txt", "src/é é.txt"})
    void typedRecordsRoundTripLosslesslyAndSupportRealCommits(String path) throws Exception {
        String hash = put(path, "special content");
        var first = new CommitService(storage).commit("special filename").snapshotId();
        byte[] historical = Files.readAllBytes(storage.resolve("snapshots/" + first + ".snapshot"));
        staging.stageDelete(Path.of(path));
        staging.stage(Path.of("keep.txt"), keepHash);
        String text = Files.readString(storage.resolve("staging/index"));
        assertTrue(text.startsWith("VRN-STAGING\t2\n"));
        assertTrue(text.contains("delete\t"));
        assertTrue(text.contains("put\t"));
        assertEquals(Map.of(path, new StagedOperation.Delete(), "keep.txt", new StagedOperation.Put(keepHash)), staging.readOperations());
        assertThrows(IllegalStateException.class, staging::readAll, "PUT-only compatibility readers must not hide DELETE");
        RepositoryLayout.validate(storage);
        var next = new SnapshotStore(storage).load(new CommitService(storage).commit("delete selected path").snapshotId());
        assertFalse(next.files().containsKey(path));
        assertEquals("special content", Files.readString(root.resolve(path)));
        assertTrue(Files.exists(storage.resolve("objects/" + hash)));
        assertArrayEquals(historical, Files.readAllBytes(storage.resolve("snapshots/" + first + ".snapshot")));
    }

    @ParameterizedTest @ValueSource(strings = {"legacy", "v1", "v2"})
    void readsEarlierIndexesWithoutRewritingAndMutatesThroughTypedOperations(String format) throws Exception {
        String text = switch (format) {
            case "legacy" -> "old.txt=" + oldHash + "\n";
            case "v1" -> MetadataCodec.encodeIndex(Map.of("old.txt", oldHash));
            default -> "VRN-STAGING\t2\nfiles\t1\nput\t" + encoded("old.txt") + "\t" + oldHash + "\n";
        };
        Files.writeString(storage.resolve("staging/index"), text);
        assertEquals(Map.of("old.txt", new StagedOperation.Put(oldHash)), staging.readOperations());
        assertEquals(Map.of("old.txt", oldHash), staging.readAll());
        assertEquals(text, Files.readString(storage.resolve("staging/index")));
        staging.stageDelete(Path.of("old.txt"));
        assertEquals(Map.of("old.txt", new StagedOperation.Delete()), staging.readOperations());
        assertTrue(Files.readString(storage.resolve("staging/index")).startsWith("VRN-STAGING\t2\n"));
    }

    static Stream<String> malformedIndexes() {
        String path = encoded("old.txt");
        return Stream.of(
                "VRN-STAGING\t3\nfiles\t0\n",
                "VRN-STAGING\t2\n",
                "VRN-STAGING\t2\nfiles\t-1\n",
                "VRN-STAGING\t2\nfiles\t2147483648\n",
                "VRN-STAGING\t2\nfiles\t1\n",
                "VRN-STAGING\t2\nfiles\t0\ndelete\t" + path + "\n",
                "VRN-STAGING\t2\nfiles\t1\nunstage\t" + path + "\n",
                "VRN-STAGING\t2\nfiles\t1\nfile\t" + path + "\tabc\n",
                "VRN-STAGING\t2\nfiles\t1\nput\t" + path + "\n",
                "VRN-STAGING\t2\nfiles\t1\nput\t" + path + "\t\n",
                "VRN-STAGING\t2\nfiles\t1\nput\t" + path + "\tbad/reference\n",
                "VRN-STAGING\t2\nfiles\t1\ndelete\t" + path + "\tabc\n",
                "VRN-STAGING\t2\nfiles\t1\ndelete\t%%%\n",
                "VRN-STAGING\t2\nfiles\t1\ndelete\tYQ\n",
                "VRN-STAGING\t2\nfiles\t1\ndelete\t/w==\n",
                "VRN-STAGING\t2\nfiles\t1\ndelete\t\n",
                "VRN-STAGING\t2\nfiles\t2\ndelete\t" + path + "\ndelete\t" + path + "\n",
                "VRN-STAGING\t2\nfiles\t2\nput\t" + path + "\tabc\ndelete\t" + path + "\n");
    }

    @ParameterizedTest @MethodSource("malformedIndexes")
    void malformedOrContradictoryRecordsAreRejectedWithoutMutation(String text) throws Exception {
        Files.writeString(storage.resolve("staging/index"), text);
        assertThrows(IllegalArgumentException.class, staging::readOperations);
        assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));
        assertThrows(Exception.class, () -> staging.stageDelete(Path.of("old.txt")));
        assertEquals(text, Files.readString(storage.resolve("staging/index")));
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
    }

    @ParameterizedTest @ValueSource(strings = {"OLD.txt", "old.txt/child.txt", "../outside.txt", ".vrn/HEAD", ".vrnauto/state"})
    void loadedOperationsStillEnforceExistingPathAndDestinationRules(String other) throws Exception {
        String raw = MetadataCodec.encodeOperations(Map.of("old.txt", new StagedOperation.Delete(), other, new StagedOperation.Put(oldHash)));
        Files.writeString(storage.resolve("staging/index"), raw);
        if (other.equals("OLD.txt") || other.equals("old.txt/child.txt")) {
            var complete = staging.readOperations();
            RepositoryLayout.validate(storage);
            var result = StagingIndex.apply(new SnapshotStore(storage).load(parent).files(), complete);
            assertFalse(result.containsKey("old.txt"));
            assertEquals(oldHash, result.get(other));
            assertEquals(raw, Files.readString(storage.resolve("staging/index")));
            String incomplete = MetadataCodec.encodeOperations(Map.of(other, new StagedOperation.Put(oldHash)));
            Files.writeString(storage.resolve("staging/index"), incomplete);
            assertThrows(IllegalArgumentException.class, () -> new CommitService(storage).commit("incomplete transition"));
            assertEquals(incomplete, Files.readString(storage.resolve("staging/index")));
            assertEquals(parent, Files.readString(storage.resolve("HEAD")));
            return;
        }
        assertThrows(IllegalArgumentException.class, staging::readOperations);
        assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));
        assertEquals(raw, Files.readString(storage.resolve("staging/index")));
    }

    @ParameterizedTest @CsvSource({"none,STAGED", "put,STAGED", "delete,ALREADY_STAGED"})
    void stageDeleteReplacesOnlySelectedIntentAndIsIdempotent(String previous, String expected) throws Exception {
        put("other.txt", "other");
        if (previous.equals("put")) staging.stage(Path.of("old.txt"), keepHash);
        if (previous.equals("delete")) staging.stageDelete(Path.of("old.txt"));
        String before = Files.readString(storage.resolve("staging/index"));
        assertEquals(StagingArea.DeletionResult.valueOf(expected), staging.stageDelete(Path.of("old.txt")));
        assertEquals(Map.of("old.txt", new StagedOperation.Delete(), "other.txt", new StagedOperation.Put(StatusCorrectnessTest.digest("other"))), staging.readOperations());
        assertEquals("old", Files.readString(root.resolve("old.txt")));
        if (previous.equals("delete")) assertEquals(before, Files.readString(storage.resolve("staging/index")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unknownDeletionIsRejectedAndUnstageWithoutSelectionDoesNotRewrite(boolean present) throws Exception {
        put("other.txt", "other");
        if (present) Files.writeString(root.resolve("unknown.txt"), "unknown");
        String before = Files.readString(storage.resolve("staging/index"));
        assertThrows(IllegalArgumentException.class, () -> staging.stageDelete(Path.of("unknown.txt")));
        assertFalse(staging.unstage(Path.of("unknown.txt")));
        assertEquals(before, Files.readString(storage.resolve("staging/index")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stagedOnlyAdditionIsCanceledEvenWhenAlreadyMissing(boolean missing) throws Exception {
        put("new.txt", "new");
        if (missing) Files.delete(root.resolve("new.txt"));
        assertEquals(StagingArea.DeletionResult.ADDITION_CANCELED, staging.stageDelete(Path.of("new.txt")));
        assertTrue(staging.readOperations().isEmpty());
        assertThrows(IllegalStateException.class, () -> new CommitService(storage).commit("canceled"));
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        assertEquals(!missing, Files.exists(root.resolve("new.txt")));
    }

    @Test void mixedStatusKeepsAllThreeStateComparisonsIndependent() throws Exception {
        staging.stageDelete(Path.of("old.txt"));
        Files.delete(root.resolve("old.txt"));
        put("keep.txt", "changed keep");
        put("new.txt", "selected");
        Files.writeString(root.resolve("new.txt"), "edited after selection");
        Files.writeString(root.resolve("loose.txt"), "untracked");
        var status = WorkingTreeStatus.compute(root);
        assertEquals(List.of("old.txt"), status.stagedDeletions);
        assertEquals(List.of("keep.txt"), status.stagedModifications);
        assertEquals(List.of("new.txt"), status.stagedNewFiles);
        assertEquals(List.of("new.txt"), status.modifiedNotStaged);
        assertEquals(List.of("loose.txt"), status.untracked);
        assertTrue(status.deleted.isEmpty());
        assertFalse(status.isClean());
        assertThrows(UnsupportedOperationException.class, () -> status.stagedDeletions.clear());
    }

    @Test void ignoredTrackedFileSelectedForDeletionRemainsVisibleWhenPresent() throws Exception {
        Files.writeString(root.resolve(".vrnignore"), ".vrn/\nold.txt\n");
        staging.stageDelete(Path.of("old.txt"));
        var status = WorkingTreeStatus.compute(root);
        assertEquals(List.of("old.txt"), status.stagedDeletions);
        assertEquals(List.of("old.txt"), status.untracked);
        assertTrue(status.deleted.isEmpty());
        Files.delete(root.resolve("old.txt"));
        assertTrue(WorkingTreeStatus.compute(root).untracked.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void statusDetectsConcurrentIntentChangesDuringUnlockedScan(boolean unstage) throws Exception {
        if (unstage) staging.stageDelete(Path.of("old.txt"));
        FileHasher interleave = new FileHasher() {
            boolean changed;
            @Override public String hash(Path file) throws Exception {
                if (!changed) {
                    changed = true;
                    if (unstage) staging.unstage(Path.of("old.txt"));
                    else staging.stageDelete(Path.of("old.txt"));
                }
                return super.hash(file);
            }
        };
        var error = assertThrows(IOException.class, () -> WorkingTreeStatus.compute(root, interleave));
        assertTrue(error.getMessage().contains("metadata changed during status"));
    }

    @Test void mixedCommitAppliesOperationsAndPreservesEarlierManifestsAndObjects() throws Exception {
        byte[] historical = Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot"));
        staging.stageDelete(Path.of("old.txt"));
        String updated = put("keep.txt", "updated");
        String added = put("new.txt", "new");
        var next = new SnapshotStore(storage).load(new CommitService(storage).commit("mixed").snapshotId());
        assertEquals(Map.of("keep.txt", updated, "new.txt", added,
                ".vrnignore", StatusCorrectnessTest.digest(Files.readString(root.resolve(".vrnignore")))), next.files());
        assertEquals(parent, next.parentId());
        assertArrayEquals(historical, Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot")));
        assertEquals("old", Files.readString(storage.resolve("objects/" + oldHash)));
        assertEquals("old", Files.readString(root.resolve("old.txt")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void excludedObjectsAreNotVerifiedButTheirHistoricalReferencesRemain(boolean corrupt) throws Exception {
        Path object = storage.resolve("objects/" + oldHash);
        if (corrupt) Files.writeString(object, "corrupt"); else Files.delete(object);
        staging.stageDelete(Path.of("old.txt"));
        var next = new SnapshotStore(storage).load(new CommitService(storage).commit("exclude damaged object").snapshotId());
        assertFalse(next.files().containsKey("old.txt"));
        assertEquals(oldHash, new SnapshotStore(storage).load(parent).files().get("old.txt"));
        assertEquals(corrupt, Files.exists(object));
        if (corrupt) assertEquals("corrupt", Files.readString(object));
    }

    @Test void retainedCorruptObjectStillBlocksCommitAndPreservesDeletionIntent() throws Exception {
        staging.stageDelete(Path.of("old.txt"));
        byte[] index = Files.readAllBytes(storage.resolve("staging/index"));
        Files.writeString(storage.resolve("objects/" + keepHash), "corrupt");
        assertThrows(ObjectIntegrityException.class, () -> new CommitService(storage).commit("blocked"));
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        assertArrayEquals(index, Files.readAllBytes(storage.resolve("staging/index")));
        assertEquals(1, snapshotCount());
        assertEquals("old", Files.readString(storage.resolve("objects/" + oldHash)));
    }

    @Test void manuallyInventedDeletionCannotActivateInvalidHistoryOrReportClean() throws Exception {
        String index = MetadataCodec.encodeOperations(Map.of("ghost.txt", new StagedOperation.Delete()));
        Files.writeString(storage.resolve("staging/index"), index);
        assertThrows(IllegalArgumentException.class, () -> new CommitService(storage).commit("invalid deletion"));
        assertThrows(IOException.class, () -> WorkingTreeStatus.compute(root));
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        assertEquals(index, Files.readString(storage.resolve("staging/index")));
        assertEquals(1, snapshotCount());
    }

    @Test void unchangedPutRetainsExistingIdenticalContentCommitPolicy() throws Exception {
        var previous = new SnapshotStore(storage).load(parent);
        staging.stage(Path.of("old.txt"), oldHash);
        assertTrue(WorkingTreeStatus.compute(root).isClean());
        var next = new SnapshotStore(storage).load(new CommitService(storage).commit("explicit identical selection").snapshotId());
        assertNotEquals(parent, next.id());
        assertEquals(previous.files(), next.files());
    }

    @ParameterizedTest @ValueSource(strings = {"../escape.txt", ".vrn/HEAD", ".VRN/HEAD", ".vrnauto/state", "old.txt/../keep.txt", ".", "C:/outside.txt"})
    void unsafeSelectionsRejectBothCommandsWithoutChangingRepository(String path) throws Exception {
        staging.stage(Path.of("keep.txt"), keepHash);
        var before = RepositoryPreflightTest.tree(storage);
        assertThrows(Exception.class, () -> staging.stageDelete(Path.of(path)));
        assertThrows(Exception.class, () -> staging.unstage(Path.of(path)));
        assertEquals(before, RepositoryPreflightTest.tree(storage));
    }

    @Test void directoriesAreNotRecursiveSelectionsAndInternalAutoStorageIsPreserved() throws Exception {
        Path auto = Files.createDirectories(root.resolve(".vrnauto/objects"));
        Files.writeString(auto.resolve("private"), "automatic state");
        Files.createDirectory(root.resolve("directory"));
        assertThrows(IOException.class, () -> staging.stageDelete(Path.of("directory")));
        assertThrows(IOException.class, () -> staging.unstage(Path.of("directory")));
        var before = RepositoryPreflightTest.tree(root.resolve(".vrnauto"));
        staging.stageDelete(Path.of("old.txt"));
        staging.unstage(Path.of("old.txt"));
        assertEquals(before, RepositoryPreflightTest.tree(root.resolve(".vrnauto")));
    }

    @Test void guardAndPreflightCoverBothNewMutationMethods() throws Exception {
        try (var ignored = RepositoryMutationGuard.acquire(storage)) {
            assertThrows(IOException.class, () -> staging.stageDelete(Path.of("old.txt")));
            assertThrows(IOException.class, () -> staging.unstage(Path.of("old.txt")));
        }
        Files.writeString(storage.resolve("config"), "version=999");
        var before = RepositoryPreflightTest.tree(storage);
        assertThrows(IOException.class, () -> staging.stageDelete(Path.of("old.txt")));
        assertThrows(IOException.class, () -> staging.unstage(Path.of("old.txt")));
        assertEquals(before, RepositoryPreflightTest.tree(storage));
    }

    @ParameterizedTest @CsvSource({"delete,write", "delete,rename", "unstage,write", "unstage,rename"})
    void failedIndexPublicationPreservesCompletePriorIntent(String command, String boundary) throws Exception {
        if (command.equals("unstage")) staging.stageDelete(Path.of("old.txt"));
        else staging.stage(Path.of("old.txt"), oldHash);
        byte[] index = Files.readAllBytes(storage.resolve("staging/index"));
        AtomicFilePublisher fail = new AtomicFilePublisher() {
            @Override protected void write(Path temporary, byte[] content) throws IOException {
                if (boundary.equals("write")) {
                    Files.writeString(temporary, "partial index");
                    throw new IOException("injected write failure");
                }
                super.write(temporary, content);
            }
            @Override protected void move(Path temporary, Path destination) throws IOException { throw new IOException("injected rename failure"); }
        };
        StagingArea broken = new StagingArea(storage, fail);
        assertThrows(IOException.class, () -> {
            if (command.equals("delete")) broken.stageDelete(Path.of("old.txt"));
            else broken.unstage(Path.of("old.txt"));
        });
        assertArrayEquals(index, Files.readAllBytes(storage.resolve("staging/index")));
        try (var entries = Files.list(storage.resolve("staging"))) {
            assertTrue(entries.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
        assertEquals("old", Files.readString(root.resolve("old.txt")));
    }

    @Test void typedIndexPublicationExposesOnlyCompleteOldOrNewSelections() throws Exception {
        staging.stageDelete(Path.of("old.txt"));
        staging.stage(Path.of("keep.txt"), keepHash);
        String before = Files.readString(storage.resolve("staging/index"));
        AtomicFilePublisher observe = new AtomicFilePublisher() {
            @Override protected void move(Path temporary, Path destination) throws IOException {
                assertEquals(before, Files.readString(destination));
                assertEquals(Map.of("old.txt", new StagedOperation.Delete()), MetadataCodec.decodeOperations(Files.readAllLines(temporary)));
                super.move(temporary, destination);
            }
        };
        assertTrue(new StagingArea(storage, observe).unstage(Path.of("keep.txt")));
        assertEquals(Map.of("old.txt", new StagedOperation.Delete()), staging.readOperations());
    }

    @Test void deleteReplacementByPutUsesV1AgainAndUnstageDeleteLeavesNoPersistedOperation() throws Exception {
        staging.stageDelete(Path.of("old.txt"));
        staging.stage(Path.of("old.txt"), oldHash);
        assertTrue(Files.readString(storage.resolve("staging/index")).startsWith("VRN-STAGING\t1\n"));
        staging.stageDelete(Path.of("old.txt"));
        assertTrue(staging.unstage(Path.of("old.txt")));
        assertEquals(Map.of(), staging.readOperations());
        assertThrows(IllegalStateException.class, () -> new CommitService(storage).commit("no intent"));
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
    }

    String put(String name, String content) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        String hash = StatusCorrectnessTest.digest(content);
        new ObjectStore(storage).store(hash, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        staging.stage(Path.of(name), hash);
        return hash;
    }

    long snapshotCount() throws IOException {
        try (var paths = Files.list(storage.resolve("snapshots"))) { return paths.count(); }
    }

    static String encoded(String path) { return Base64.getEncoder().encodeToString(path.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}
