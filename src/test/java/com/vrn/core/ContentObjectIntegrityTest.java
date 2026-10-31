package com.vrn.core;

import com.vrn.VrnApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ContentObjectIntegrityTest {
    @TempDir Path root;
    private Path storage;
    private Path head;
    private Path index;
    private String hash;
    private byte[] content;

    @BeforeEach
    void initialize() throws Exception {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        head = storage.resolve("HEAD");
        index = storage.resolve("staging/index");
        content = "real object content".getBytes(StandardCharsets.UTF_8);
        hash = digest(content);
        new ObjectStore(storage).store(hash, content);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escaped-object", "not-a-sha256", "abc123"})
    void malformedObjectIdentifiersAreRejectedBeforePathUse(String identifier) {
        Exception error = assertThrows(Exception.class, () -> new ObjectStore(storage).store(identifier, content));
        assertTrue(error.getMessage().contains("object identifier"), error.getMessage());
        assertFalse(Files.exists(storage.resolve("escaped-object")));
    }

    @Test
    void mismatchingIncomingContentIsRejectedBeforePublication() throws Exception {
        String other = digest("different".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> new ObjectStore(storage).store(other, content));
        assertFalse(Files.exists(storage.resolve("objects").resolve(other)));
    }

    @Test
    void corruptExistingObjectCannotBeReusedOrSilentlyOverwritten() throws Exception {
        Path object = storage.resolve("objects").resolve(hash);
        Files.writeString(object, "corrupt");
        assertThrows(IOException.class, () -> new ObjectStore(storage).store(hash, content));
        assertEquals("corrupt", Files.readString(object));
    }

    @Test
    void missingStagedObjectCannotAdvanceHeadOrClearStaging() throws Exception {
        new StagingArea(storage).stage(Path.of("file.txt"), hash);
        byte[] staged = Files.readAllBytes(index);
        Files.delete(storage.resolve("objects").resolve(hash));

        assertThrows(IOException.class, () -> new CommitService(storage).commit("missing staged object"));

        assertEquals("NONE", Files.readString(head));
        assertArrayEquals(staged, Files.readAllBytes(index));
        assertNoCandidate();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrCorruptInheritedObjectPreventsPublication(boolean corrupt) throws Exception {
        new StagingArea(storage).stage(Path.of("old.txt"), hash);
        String parent = new CommitService(storage).commit("valid parent").snapshotId();
        Path previous = storage.resolve("snapshots").resolve(parent + ".snapshot");
        byte[] history = Files.readAllBytes(previous);
        byte[] next = "new contents".getBytes(StandardCharsets.UTF_8);
        String nextHash = digest(next);
        new ObjectStore(storage).store(nextHash, next);
        new StagingArea(storage).stage(Path.of("new.txt"), nextHash);
        byte[] staged = Files.readAllBytes(index);
        Path inherited = storage.resolve("objects").resolve(hash);
        if (corrupt) Files.writeString(inherited, "truncated"); else Files.delete(inherited);

        assertThrows(IOException.class, () -> new CommitService(storage).commit("invalid inherited object"));

        assertEquals(parent, Files.readString(head));
        assertArrayEquals(staged, Files.readAllBytes(index));
        assertArrayEquals(history, Files.readAllBytes(previous));
        try (var paths = Files.list(storage.resolve("snapshots"))) { assertEquals(1, paths.count()); }
        assertFalse(Files.exists(storage.resolve("staging/commit.pending")));
    }

    @Test
    void directoryInsteadOfObjectIsNotValidContent() throws Exception {
        Path object = storage.resolve("objects").resolve(hash);
        Files.delete(object);
        Files.createDirectory(object);
        new StagingArea(storage).stage(Path.of("file.txt"), hash);
        byte[] before = Files.readAllBytes(index);

        assertThrows(IOException.class, () -> new CommitService(storage).commit("wrong object type"));

        assertEquals("NONE", Files.readString(head));
        assertArrayEquals(before, Files.readAllBytes(index));
        assertNoCandidate();
    }

    @Test
    void malformedReferenceInStagingCannotBecomeActive() throws Exception {
        new StagingArea(storage).stage(Path.of("file.txt"), "not-a-digest");
        byte[] before = Files.readAllBytes(index);

        assertThrows(IOException.class, () -> new CommitService(storage).commit("invalid reference"));

        assertEquals("NONE", Files.readString(head));
        assertArrayEquals(before, Files.readAllBytes(index));
        assertNoCandidate();
    }

    @Test
    void unreadableCandidateFailsBeforeAnyMetadataPublication() throws Exception {
        new StagingArea(storage).stage(Path.of("file.txt"), hash);
        byte[] staged = Files.readAllBytes(index);
        ObjectStore denied = new ObjectStore(storage) {
            @Override protected SeekableByteChannel openObject(Path object) throws IOException {
                throw new AccessDeniedException(object.toString());
            }
        };
        ObjectIntegrityException error = assertThrows(ObjectIntegrityException.class,
                () -> new CommitService(storage, new AtomicFilePublisher(), denied).commit("unreadable"));
        assertEquals(ObjectIntegrityException.Kind.UNREADABLE, error.kind());
        assertEquals("NONE", Files.readString(head));
        assertArrayEquals(staged, Files.readAllBytes(index));
        assertNoCandidate();
    }

    @Test
    void completeCandidateHashesSharedObjectsOnce_thenRechecksOnANewerAttempt() throws Exception {
        StagingArea staging = new StagingArea(storage);
        staging.stage(Path.of("first.txt"), hash);
        staging.stage(Path.of("second.txt"), hash);
        AtomicInteger opens = new AtomicInteger();
        ObjectStore counted = new ObjectStore(storage) {
            @Override protected SeekableByteChannel openObject(Path object) throws IOException {
                opens.incrementAndGet();
                return super.openObject(object);
            }
        };
        CommitService commits = new CommitService(storage, new AtomicFilePublisher(), counted);
        String active = commits.commit("shared valid object").snapshotId();
        assertEquals(1, opens.get());
        assertEquals(Map.of("first.txt", hash, "second.txt", hash), new SnapshotStore(storage).load(active).files());
        staging.stage(Path.of("third.txt"), hash);
        byte[] before = Files.readAllBytes(index);
        Files.writeString(storage.resolve("objects").resolve(hash), "corrupt");

        assertThrows(ObjectIntegrityException.class, () -> commits.commit("must reverify"));

        assertEquals(2, opens.get());
        assertEquals(active, Files.readString(head));
        assertArrayEquals(before, Files.readAllBytes(index));
    }

    @Test
    void retryAfterExplicitlyRestoringAMissingObjectUsesPreservedStaging() throws Exception {
        new StagingArea(storage).stage(Path.of("file.txt"), hash);
        Files.delete(storage.resolve("objects").resolve(hash));
        assertThrows(ObjectIntegrityException.class, () -> new CommitService(storage).commit("missing"));
        // Test-fixture intervention, not an automatic repair operation.
        new ObjectStore(storage).store(hash, content);

        String active = new CommitService(storage).commit("valid retry").snapshotId();

        assertEquals(active, Files.readString(head));
        assertEquals(Map.of("file.txt", hash), new SnapshotStore(storage).load(active).files());
        assertTrue(new StagingArea(storage).readAll().isEmpty());
    }

    @Test
    void unpublishedCommitRecoveryPreservesStagingBeforeALaterIntegrityFailure() throws Exception {
        new StagingArea(storage).stage(Path.of("file.txt"), hash);
        AtomicFilePublisher failedHead = new AtomicFilePublisher() {
            @Override protected void move(Path temporary, Path destination) throws IOException {
                if (destination.equals(head)) throw new IOException("injected HEAD failure");
                super.move(temporary, destination);
            }
        };
        assertThrows(IOException.class, () -> new CommitService(storage, failedHead).commit("not active"));
        assertTrue(Files.exists(storage.resolve("staging/commit.pending")));
        byte[] before = Files.readAllBytes(index);
        Path candidate;
        try (var files = Files.list(storage.resolve("snapshots"))) { candidate = files.findFirst().orElseThrow(); }
        byte[] unpublished = Files.readAllBytes(candidate);
        Files.delete(storage.resolve("objects").resolve(hash));

        assertThrows(ObjectIntegrityException.class, () -> new CommitService(storage).commit("bad retry"));

        assertEquals("NONE", Files.readString(head));
        assertArrayEquals(before, Files.readAllBytes(index));
        assertArrayEquals(unpublished, Files.readAllBytes(candidate));
        assertFalse(Files.exists(storage.resolve("staging/commit.pending")), "Prior unpublished cleanup barrier is retired normally");
    }

    @Test
    void actualCliIntegrityFailureReturnsNonzeroAndPreservesExistingHistoryAndStaging() throws Exception {
        new StagingArea(storage).stage(Path.of("parent.txt"), hash);
        String parent = new CommitService(storage).commit("valid history").snapshotId();
        byte[] previous = Files.readAllBytes(storage.resolve("snapshots").resolve(parent + ".snapshot"));
        Files.writeString(root.resolve("new.txt"), "new real CLI content");
        Run added = cli("add", "new.txt");
        assertEquals(0, added.code(), added.output());
        byte[] before = Files.readAllBytes(index);
        Files.writeString(storage.resolve("objects").resolve(hash), "corrupt inherited bytes");

        Run rejected = cli("commit", "-m", "invalid inherited object");

        assertEquals(1, rejected.code(), rejected.output());
        assertTrue(rejected.output().contains("DIGEST_MISMATCH"));
        assertTrue(rejected.output().contains(hash));
        assertTrue(rejected.output().contains("staging was not cleared"));
        assertFalse(rejected.output().contains("Committed snapshot"));
        assertFalse(rejected.output().contains("Exception in thread"));
        assertEquals(parent, Files.readString(head));
        assertArrayEquals(before, Files.readAllBytes(index));
        assertArrayEquals(previous, Files.readAllBytes(storage.resolve("snapshots").resolve(parent + ".snapshot")));
    }

    @Test
    void onlyFinalCandidateReferencesAreVerified_notSupersededHistoricalObjects() throws Exception {
        new StagingArea(storage).stage(Path.of("file.txt"), hash);
        String parent = new CommitService(storage).commit("old content").snapshotId();
        Path metadata = storage.resolve("snapshots").resolve(parent + ".snapshot");
        byte[] history = Files.readAllBytes(metadata);
        Files.delete(storage.resolve("objects").resolve(hash));
        byte[] replacement = "replacement contents".getBytes(StandardCharsets.UTF_8);
        String replacementId = digest(replacement);
        new ObjectStore(storage).store(replacementId, replacement);
        new StagingArea(storage).stage(Path.of("file.txt"), replacementId);

        String active = new CommitService(storage).commit("valid replacement content").snapshotId();

        assertEquals(Map.of("file.txt", replacementId), new SnapshotStore(storage).load(active).files());
        assertArrayEquals(history, Files.readAllBytes(metadata));
        assertFalse(Files.exists(storage.resolve("objects").resolve(hash)), "Historical corruption is not repaired");
    }

    @Test
    void actualCliAddRefusesCorruptReuse_withoutClaimingWholeCommandRollback() throws Exception {
        Files.write(root.resolve("same.txt"), content);
        Files.writeString(storage.resolve("objects").resolve(hash), "bad stored bytes");

        Run result = cli("add", "same.txt");

        assertEquals(1, result.code(), result.output());
        assertTrue(result.output().contains("DIGEST_MISMATCH"));
        assertFalse(result.output().contains("Files staged successfully"));
        assertFalse(Files.exists(index));
        assertEquals("bad stored bytes", Files.readString(storage.resolve("objects").resolve(hash)));
    }

    private void assertNoCandidate() throws IOException {
        try (var paths = Files.list(storage.resolve("snapshots"))) { assertEquals(0, paths.count()); }
        assertFalse(Files.exists(storage.resolve("staging/commit.pending")));
    }

    private String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Run cli(String... args) throws Exception {
        String classpath = location(VrnApplication.class) + File.pathSeparator + location(CommandLine.class);
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        command.addAll(Arrays.asList("-cp", classpath, VrnApplication.class.getName()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI timed out");
            return new Run(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
    private String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
    private record Run(int code, String output) { }
}
