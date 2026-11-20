package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AtomicBatchStagingTest {
    @TempDir Path root;
    Path storage;
    Path index;
    StagingArea staging;
    byte[] baseline;
    String head;
    Map<String, String> history;

    @BeforeEach void prepare() throws Exception {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        index = storage.resolve("staging/index");
        staging = new StagingArea(storage);
        write("batch/0.txt", "old zero");
        write("batch/1.txt", "old one");
        write("obsolete.txt", "old obsolete");
        new AddService(root).add(Path.of("."));
        new CommitService(storage).commit("fixture parent");
        new AddService(root).add(Path.of("batch/0.txt"));
        write("pending.txt", "existing pending PUT");
        new AddService(root).add(Path.of("pending.txt"));
        staging.stageDelete(Path.of("batch/1.txt"));
        staging.stageDelete(Path.of("obsolete.txt"));
        for (int i = 0; i < 4; i++) write("batch/" + i + ".txt", "captured contents " + i);
        checkpoint();
    }

    @Test void completeCandidateIsPublishedExactlyOnceUnderTheSameGuard() throws Exception {
        AtomicInteger publications = new AtomicInteger();
        var expected = staging.readOperations();
        for (int i = 0; i < 4; i++) expected.put("batch/" + i + ".txt", new StagedOperation.Put(StatusCorrectnessTest.digest("captured contents " + i)));
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override protected void write(Path temporary, byte[] content) throws IOException {
                unchanged();
                assertBusy();
                var candidate = MetadataCodec.decodeOperations(List.of(new String(content, java.nio.charset.StandardCharsets.UTF_8).split("\n")));
                assertEquals(6, candidate.size());
                assertInstanceOf(StagedOperation.Delete.class, candidate.get("obsolete.txt"));
                assertEquals(expected, candidate);
                super.write(temporary, content);
            }
            @Override protected void move(Path temporary, Path destination) throws IOException {
                assertEquals(index, destination);
                unchanged();
                publications.incrementAndGet();
                super.move(temporary, destination);
            }
        };
        FileScanner scanner = new FileScanner() {
            @Override List<SelectedFile> selectForAddLocked(Path project, Path target, Set<String> known) throws Exception {
                assertBusy();
                unchanged();
                return super.selectForAddLocked(project, target, known);
            }
        };
        ObjectStore objects = new ObjectStore(storage) {
            @Override String captureLocked(Path project, SelectedFile selected) throws Exception {
                assertBusy();
                unchanged();
                return super.captureLocked(project, selected);
            }
        };
        var result = service(scanner, objects, publisher).add(Path.of("batch"));
        assertEquals(4, result.selectedFiles());
        assertNull(result.warning());
        assertEquals(1, publications.get());
        assertEquals(6, staging.readOperations().size());
        assertInstanceOf(StagedOperation.Delete.class, staging.readOperations().get("obsolete.txt"));
        unchangedHistory();
        assertTrue(WorkingTreeStatus.compute(root).stagedDeletions.contains("obsolete.txt"));
        for (int i = 0; i < 4; i++) new ObjectStore(storage).verify(StatusCorrectnessTest.digest("captured contents " + i));
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void captureFailureAtEveryPositionPreservesMixedIndex(int failureAt) throws Exception {
        var calls = new AtomicInteger();
        ContentCapture capture = new ContentCapture() {
            @Override String capture(Path project, SelectedFile selected, Path temporary) throws Exception {
                if (calls.getAndIncrement() == failureAt) throw new IOException("injected read failure");
                return super.capture(project, selected, temporary);
            }
        };
        var objectsBefore = RepositoryPreflightTest.tree(storage.resolve("objects"));
        assertUnchangedFailure(service(new FileScanner(), new ObjectStore(storage, new AtomicFilePublisher(), capture), new AtomicFilePublisher()));
        assertEquals(failureAt + 1, calls.get());
        assertTrue(RepositoryPreflightTest.tree(storage.resolve("objects")).entrySet().containsAll(objectsBefore.entrySet()));
        for (var file : new FileScanner().scanForAdd(root, Path.of("batch"))) {
            if (Files.exists(storage.resolve("objects/" + file.hash()))) new ObjectStore(storage).verify(file.hash());
        }
    }

    @Test void disappearanceBeforeCaptureFailsRatherThanSkippingTheFile() throws Exception {
        ContentCapture capture = new ContentCapture() {
            int calls;
            @Override protected java.io.InputStream openSource(Path file) throws IOException {
                if (++calls == 3) Files.delete(file);
                return super.openSource(file);
            }
        };
        Exception failure = assertUnchangedFailure(service(new FileScanner(), new ObjectStore(storage, new AtomicFilePublisher(), capture), new AtomicFilePublisher()));
        assertTrue(failure.getMessage().contains("Preparing PUT"));
    }

    @Test void discoveryFailureAfterInspectionDoesNotPublishOrPrepareObjects() throws Exception {
        FileScanner scanner = new FileScanner() {
            @Override List<SelectedFile> selectForAddLocked(Path project, Path target, Set<String> known) throws Exception {
                super.selectForAddLocked(project, target, known);
                throw new IOException("injected traversal failure");
            }
        };
        var objects = RepositoryPreflightTest.tree(storage.resolve("objects"));
        assertUnchangedFailure(service(scanner, new ObjectStore(storage), new AtomicFilePublisher()));
        assertEquals(objects, RepositoryPreflightTest.tree(storage.resolve("objects")));
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void objectPublicationFailureAtEveryPositionPreservesMixedIndex(int failureAt) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ObjectStore objects = new ObjectStore(storage, new AtomicFilePublisher() {
            @Override protected void linkObject(Path temporary, Path destination) throws IOException {
                if (calls.getAndIncrement() == failureAt) throw new IOException("injected object publication failure");
                super.linkObject(temporary, destination);
            }
        });
        assertUnchangedFailure(service(new FileScanner(), objects, new AtomicFilePublisher()));
        assertEquals(failureAt + 1, calls.get());
        // Successfully prepared immutable objects are retained and verified, never rolled back.
        for (var file : new FileScanner().scanForAdd(root, Path.of("batch"))) {
            if (Files.exists(storage.resolve("objects/" + file.hash()))) new ObjectStore(storage).verify(file.hash());
        }
        assertEquals(4, new AddService(root).add(Path.of("batch")).selectedFiles());
    }

    @ParameterizedTest @ValueSource(strings = {"write", "move", "unsupported", "move-and-cleanup"})
    void indexPublicationFailuresPreserveRawBytesAndPreparedObjects(String point) throws Exception {
        AtomicFilePublisher publisher = failingPublisher(point);
        assertUnchangedFailure(service(new FileScanner(), new ObjectStore(storage), publisher));
        for (int i = 0; i < 4; i++) new ObjectStore(storage).verify(StatusCorrectnessTest.digest("captured contents " + i));
    }

    @ParameterizedTest @ValueSource(strings = {"after-move", "after-move-and-cleanup"})
    void confirmedPublicationRemainsSuccessWithWarningAfterFollowupFailure(String point) throws Exception {
        var result = service(new FileScanner(), new ObjectStore(storage), failingPublisher(point)).add(Path.of("batch"));
        assertEquals(4, result.selectedFiles());
        assertNotNull(result.warning());
        assertTrue(result.warning().contains("complete staging index is published"));
        assertEquals(6, staging.readOperations().size());
        for (int i = 0; i < 4; i++) assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest("captured contents " + i)), staging.readOperations().get("batch/" + i + ".txt"));
        unchangedHistory();
    }

    @Test void unchangedCandidateDoesNotFalselyConfirmAFailedReplacement() throws Exception {
        new AddService(root).add(Path.of("batch"));
        checkpoint();
        Exception result = assertUnchangedFailure(service(new FileScanner(), new ObjectStore(storage), failingPublisher("move")));
        assertFalse(result.getMessage().contains("complete staging index is published"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void uncertainPublicationNeverClaimsRollbackOrOverwritesObservedState(boolean unreadable) throws Exception {
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override protected void move(Path temporary, Path destination) throws IOException {
                super.move(temporary, destination);
                // Deliberate external-damage simulation, outside the cooperating-writer contract.
                if (unreadable) { Files.delete(index); Files.createDirectory(index); }
                else Files.writeString(index, "foreign state");
                throw new IOException("ambiguous rename result");
            }
        };
        Exception failure = assertThrows(IOException.class, () -> service(new FileScanner(), new ObjectStore(storage), publisher).add(Path.of("batch")));
        assertTrue(failure.getMessage().contains("uncertain"));
        assertFalse(failure.getMessage().contains("unchanged"));
        if (unreadable) assertTrue(Files.isDirectory(index));
        else assertEquals("foreign state", Files.readString(index));
        unchangedHistory();
    }

    @Test void lateCandidatePathConflictAbortsAfterObjectPreparation() throws Exception {
        // A formerly selected file is now a directory; Task 8D replacement is intentionally unsupported.
        var entries = staging.readOperations();
        entries.keySet().removeIf(path -> path.startsWith("batch/"));
        entries.put("batch", new StagedOperation.Put(StatusCorrectnessTest.digest("old zero")));
        Files.writeString(index, MetadataCodec.encodeOperations(entries));
        checkpoint();
        assertUnchangedFailure(new AddService(root));
        new ObjectStore(storage).verify(StatusCorrectnessTest.digest("captured contents 3"));
    }

    @ParameterizedTest @ValueSource(strings = {"malformed", "missing", "corrupt"})
    void invalidExistingPutCannotEnterThePublishedCandidate(String fault) throws Exception {
        var entries = staging.readOperations();
        String id = fault.equals("malformed") ? "opaque-token" : StatusCorrectnessTest.digest("unavailable selected contents");
        entries.put("unrelated.txt", new StagedOperation.Put(id));
        Files.writeString(index, MetadataCodec.encodeOperations(entries));
        if (fault.equals("corrupt")) Files.writeString(storage.resolve("objects/" + id), "wrong bytes");
        checkpoint();
        assertUnchangedFailure(new AddService(root));
    }

    @Test void capturedHashAndObjectDescribeCapturedBytesEvenAfterLaterWorkingEdit() throws Exception {
        ContentCapture capture = new ContentCapture() {
            @Override String capture(Path project, SelectedFile selected, Path temporary) throws Exception {
                String hash = super.capture(project, selected, temporary);
                Files.writeString(project.resolve(selected.source()), "edited after capture");
                return hash;
            }
        };
        service(new FileScanner(), new ObjectStore(storage, new AtomicFilePublisher(), capture), new AtomicFilePublisher()).add(Path.of("batch"));
        for (int i = 0; i < 4; i++) {
            String path = "batch/" + i + ".txt";
            String hash = StatusCorrectnessTest.digest("captured contents " + i);
            assertEquals(new StagedOperation.Put(hash), staging.readOperations().get(path));
            new ObjectStore(storage).verify(hash);
            assertTrue(WorkingTreeStatus.compute(root).modifiedNotStaged.contains(path));
        }
    }

    @Test void recoveryEstablishesANewBaselineBeforeAnAddFailure() throws Exception {
        AtomicFilePublisher blocked = blockDelete(index);
        assertNotNull(new CommitService(storage, blocked).commit("published with deferred cleanup").warning());
        assertTrue(Files.exists(index));
        head = Files.readString(storage.resolve("HEAD"));
        history = RepositoryPreflightTest.tree(storage.resolve("snapshots"));
        FileScanner failure = new FileScanner() {
            @Override List<SelectedFile> selectForAddLocked(Path project, Path target, Set<String> known) throws Exception {
                assertFalse(Files.exists(index));
                assertFalse(Files.exists(storage.resolve("staging/commit.pending")));
                throw new IOException("injected post-recovery discovery failure");
            }
        };
        Exception result = assertThrows(IOException.class, () -> service(failure, new ObjectStore(storage), new AtomicFilePublisher()).add(Path.of("batch")));
        assertTrue(result.getMessage().contains("post-recovery baseline"));
        assertFalse(Files.exists(index));
        assertFalse(Files.exists(storage.resolve("staging/commit.pending")));
        unchangedHistory();
        new AddService(root).add(Path.of("batch"));
        var fresh = staging.readOperations();
        staging.recover();
        assertEquals(fresh, staging.readOperations());
    }

    @Test void blockedRecoveryOrChangedFingerprintPreventsNewStaging() throws Exception {
        AtomicFilePublisher blocked = blockDelete(index);
        assertNotNull(new CommitService(storage, blocked).commit("published with deferred cleanup").warning());
        checkpoint();
        assertThrows(IOException.class, () -> service(new FileScanner(), new ObjectStore(storage), blocked).add(Path.of("batch")));
        unchanged();
        Files.writeString(index, MetadataCodec.encodeOperations(Map.of("pending.txt", new StagedOperation.Put(StatusCorrectnessTest.digest("existing pending PUT")))));
        checkpoint();
        assertThrows(IOException.class, () -> new AddService(root).add(Path.of("batch")));
        unchanged();
        assertTrue(Files.exists(storage.resolve("staging/commit.pending")));
    }

    @Test void busyRepositoryRejectsWholeBatchAndCanRetryAfterRelease() throws Exception {
        try (var guard = RepositoryMutationGuard.acquire(storage)) {
            assertThrows(IOException.class, () -> new AddService(root).add(Path.of("batch")));
            unchanged();
        }
        assertEquals(4, new AddService(root).add(Path.of("batch")).selectedFiles());
    }

    private AtomicFilePublisher failingPublisher(String point) {
        return new AtomicFilePublisher() {
            @Override protected void write(Path temporary, byte[] content) throws IOException {
                unchanged();
                if (point.equals("write")) { Files.writeString(temporary, "partial"); throw new IOException("injected index temporary write failure"); }
                super.write(temporary, content);
            }
            @Override protected void move(Path temporary, Path destination) throws IOException {
                unchanged();
                if (point.equals("unsupported")) throw new AtomicMoveNotSupportedException("temporary", "index", "injected");
                if (point.equals("move") || point.equals("move-and-cleanup")) throw new IOException("injected index replacement failure");
                super.move(temporary, destination);
                throw new IOException("injected post-publication failure");
            }
            @Override public void delete(Path path) throws IOException {
                if (point.endsWith("and-cleanup")) throw new IOException("injected nonessential temporary cleanup failure");
                super.delete(path);
            }
        };
    }
    private AtomicFilePublisher blockDelete(Path target) {
        return new AtomicFilePublisher() {
            @Override public void delete(Path path) throws IOException {
                if (path.equals(target)) throw new IOException("injected blocked recovery");
                super.delete(path);
            }
        };
    }
    private AddService service(FileScanner scanner, ObjectStore objects, AtomicFilePublisher publisher) { return new AddService(root, scanner, objects, publisher); }
    private Exception assertUnchangedFailure(AddService service) throws Exception {
        Exception failure = assertThrows(Exception.class, () -> service.add(Path.of("batch")));
        assertTrue(failure.getMessage().contains("no partial staging-index update was published"), failure.getMessage());
        unchanged();
        unchangedHistory();
        return failure;
    }
    private void assertBusy() { assertThrows(IOException.class, () -> { try (var ignored = RepositoryMutationGuard.acquire(storage)) { fail("Nested mutation acquired the guard"); } }); }
    private void write(String relative, String contents) throws IOException { Files.createDirectories(root.resolve(relative).getParent()); Files.writeString(root.resolve(relative), contents); }
    private void checkpoint() throws Exception { baseline = Files.readAllBytes(index); head = Files.readString(storage.resolve("HEAD")); history = RepositoryPreflightTest.tree(storage.resolve("snapshots")); }
    private void unchanged() throws IOException { assertArrayEquals(baseline, Files.readAllBytes(index)); }
    private void unchangedHistory() throws Exception { assertEquals(head, Files.readString(storage.resolve("HEAD"))); assertEquals(history, RepositoryPreflightTest.tree(storage.resolve("snapshots"))); }
}
