package com.vrn.core;

import com.vrn.VrnApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ManifestIntegrityIntegrationTest {

    @TempDir
    Path root;
    private Path storage;
    private Path head;
    private Path index;
    private String hash;

    @BeforeEach
    void initialize() throws Exception {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        head = storage.resolve("HEAD");
        index = storage.resolve("staging/index");
        byte[] content = "real stored project contents".getBytes(StandardCharsets.UTF_8);
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        new ObjectStore(storage).store(hash, content);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", ".vrn/config", ".vrnauto/HEAD", "C:/escape", "node/../child",
            "src\\file", "CON.txt", "trailing."})
    void directSnapshotPublicationRejectsInvalidPaths_beforeWritingMetadata(String path) throws Exception {
        String parent = commitParent("kept.txt");
        new StagingArea(storage).stage(Path.of("pending.txt"), hash);
        byte[] staged = Files.readAllBytes(index);
        byte[] history = Files.readAllBytes(snapshotPath(parent));
        Snapshot bad = new Snapshot("invalid-candidate", parent, 1, "rejected", Map.of(path, hash));

        assertThrows(IllegalArgumentException.class, () -> new SnapshotStore(storage).save(bad));

        assertEquals(parent, Files.readString(head));
        assertArrayEquals(staged, Files.readAllBytes(index));
        assertArrayEquals(history, Files.readAllBytes(snapshotPath(parent)));
        assertFalse(Files.exists(snapshotPath("invalid-candidate")));
        assertEquals(1, snapshotCount());
        assertFalse(Files.exists(storage.resolve("staging/commit.pending")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "../escape", "node/../child", "node/.", ".vrn/config", ".vrnauto/HEAD",
            "C:relative", "CON.txt"})
    void stagingRejectsInvalidIndividualPaths_withoutChangingTheIndex(String path) throws Exception {
        StagingArea staging = new StagingArea(storage);
        staging.stage(Path.of("kept.txt"), hash);
        byte[] before = Files.readAllBytes(index);

        assertThrows(IllegalArgumentException.class, () -> staging.stage(Path.of(path), hash));

        assertArrayEquals(before, Files.readAllBytes(index));
        assertEquals(Map.of("kept.txt", hash), staging.readAll());
    }

    @ParameterizedTest
    @CsvSource({"a.txt,A.TXT", "node,node/child", "node/child,node", "Node,node/child", "Src/a,src/b"})
    void stagingRejectsContradictoryIndex_withoutReplacingExistingSpelling(String first, String second) throws Exception {
        StagingArea staging = new StagingArea(storage);
        staging.stage(Path.of(first), hash);
        byte[] before = Files.readAllBytes(index);

        assertThrows(IllegalArgumentException.class, () -> staging.stage(Path.of(second), hash));

        assertArrayEquals(before, Files.readAllBytes(index));
        assertEquals(Map.of(first, hash), staging.readAll());
    }

    @ParameterizedTest
    @CsvSource({"node,node/child.txt", "node/child.txt,node", "Node,node/child.txt", "node/child.txt,Node",
            "a.txt,A.TXT", "src/A.txt,src/a.txt", "Src/a.txt,src/b.txt"})
    void inheritedAndStagedConflicts_cannotPublishOrAdvanceHead(String inherited, String stagedPath) throws Exception {
        String parent = commitParent(inherited);
        StagingArea staging = new StagingArea(storage);
        staging.stage(Path.of(stagedPath), hash);
        byte[] before = Files.readAllBytes(index);
        byte[] previousSnapshot = Files.readAllBytes(snapshotPath(parent));
        AtomicFilePublisher noPublication = new AtomicFilePublisher() {
            @Override
            protected void write(Path temporary, byte[] content) {
                fail("Invalid candidates must be rejected before any metadata publication attempt");
            }
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CommitService(storage, noPublication).commit("must be rejected"));

        assertTrue(error.getMessage().contains(inherited), error.getMessage());
        assertTrue(error.getMessage().contains(stagedPath), error.getMessage());
        assertEquals(parent, Files.readString(head));
        assertEquals(1, snapshotCount());
        assertArrayEquals(previousSnapshot, Files.readAllBytes(snapshotPath(parent)));
        assertArrayEquals(before, Files.readAllBytes(index));
        assertEquals(Map.of(stagedPath, hash), staging.readAll());
        assertFalse(Files.exists(storage.resolve("staging/commit.pending")));
    }

    @Test
    void malformedStoredVersionedSnapshots_failClearlyWithoutRewritingTheirBytes() throws Exception {
        List<Map<String, String>> invalid = List.of(Map.of("../escape", hash), Map.of(".VRN/HEAD", hash),
                Map.of("a.txt", hash, "A.TXT", hash), Map.of("node", hash, "node/child", hash),
                Map.of("src\\file.txt", hash));
        SnapshotStore snapshots = new SnapshotStore(storage);
        for (int i = 0; i < invalid.size(); i++) {
            String id = "bad-" + i;
            String raw = MetadataCodec.encodeSnapshot(new Snapshot(id, "NONE", 1, "raw metadata", invalid.get(i)));
            Files.writeString(snapshotPath(id), raw);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> snapshots.load(id));

            assertTrue(error.getMessage().startsWith("Invalid VRN manifest:"));
            assertEquals(raw, Files.readString(snapshotPath(id)));
        }
        assertEquals("NONE", Files.readString(head));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", ".vrn/HEAD", "C:/outside", "a\\b.txt"})
    void invalidLegacyPathsAreRejectedWithoutMigration(String path) throws Exception {
        String raw = "id=legacy\nparent=NONE\ntimestamp=1\nmessage=legacy\n[files]\n" + path + "=" + hash + "\n";
        Files.writeString(snapshotPath("legacy"), raw);

        assertThrows(IllegalArgumentException.class, () -> new SnapshotStore(storage).load("legacy"));

        assertEquals(raw, Files.readString(snapshotPath("legacy")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void injectedStagingCannotBypassCommitOrLayoutValidation(boolean versioned) throws Exception {
        String raw = versioned ? MetadataCodec.encodeIndex(Map.of(".vrnauto/HEAD", hash))
                : "../outside=" + hash + "\n";
        Files.writeString(index, raw);

        assertThrows(IllegalArgumentException.class, () -> new StagingArea(storage).readAll());
        assertThrows(IllegalArgumentException.class, () -> new CommitService(storage).commit("bad staging"));
        assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertEquals(raw, Files.readString(index));
        assertEquals("NONE", Files.readString(head));
        assertEquals(0, snapshotCount());
        assertFalse(Files.exists(storage.resolve("staging/commit.pending")));
    }

    @Test
    void invalidActiveManifest_blocksCommitAndRepositoryValidationWithoutChangingHistory() throws Exception {
        String raw = MetadataCodec.encodeSnapshot(new Snapshot("bad-parent", "NONE", 1, "unsafe",
                Map.of("node", hash, "node/child", hash)));
        Files.writeString(snapshotPath("bad-parent"), raw);
        Files.writeString(head, "bad-parent");
        new StagingArea(storage).stage(Path.of("pending.txt"), hash);
        byte[] before = Files.readAllBytes(index);

        assertThrows(IllegalArgumentException.class, () -> new CommitService(storage).commit("invalid inherited state"));
        assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertEquals("bad-parent", Files.readString(head));
        assertEquals(raw, Files.readString(snapshotPath("bad-parent")));
        assertArrayEquals(before, Files.readAllBytes(index));
        assertEquals(1, snapshotCount());
    }

    @Test
    void duplicateStoredDestinations_areRejectedByTheExistingMetadataDecoder() throws Exception {
        String raw = MetadataCodec.encodeSnapshot(new Snapshot("duplicate", "NONE", 1, "raw", Map.of("file.txt", hash)));
        String entry = raw.lines().filter(line -> line.startsWith("file\t")).findFirst().orElseThrow();
        raw = raw.replace("files\t1", "files\t2") + entry + "\n";
        Files.writeString(snapshotPath("duplicate"), raw);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SnapshotStore(storage).load("duplicate"));

        assertTrue(error.getMessage().contains("duplicate file path"));
        assertEquals(raw, Files.readString(snapshotPath("duplicate")));
    }

    @Test
    void validUnicodePunctuationAndNestedReservedNames_publishWithoutChangingSpelling() throws Exception {
        String parent = commitParent("ordinary.txt");
        StagingArea staging = new StagingArea(storage);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ordinary.txt", hash);
        for (String path : List.of("config=production.txt", "#notes.txt", "資料/नमस्ते/é😀.txt",
                "src/.vrn/example.txt", "src/.vrnauto/example.txt")) {
            staging.stage(Path.of(path), hash);
            expected.put(path, hash);
        }

        CommitService.Result committed = new CommitService(storage).commit("valid manifest");

        assertNull(committed.warning());
        assertEquals(committed.snapshotId(), Files.readString(head));
        Snapshot snapshot = new SnapshotStore(storage).load(committed.snapshotId());
        assertEquals(parent, snapshot.parentId());
        assertEquals(expected, snapshot.files());
        assertTrue(staging.readAll().isEmpty());
        assertEquals(2, snapshotCount());
        RepositoryLayout.validate(storage);
    }

    @Test
    void snapshotCopiesAndProtectsItsManifest_beforeValidationAndEncoding() throws Exception {
        Map<String, String> callerMap = new LinkedHashMap<>(Map.of("kept.txt", hash));
        Snapshot snapshot = new Snapshot("immutable", "NONE", 1, "frozen", callerMap);
        callerMap.put("../escape", hash);

        assertThrows(UnsupportedOperationException.class, () -> snapshot.files().put("another.txt", hash));
        new SnapshotStore(storage).save(snapshot);

        assertEquals(Map.of("kept.txt", hash), new SnapshotStore(storage).load("immutable").files());
    }

    @Test
    void actualCliRejectsAnUnsupportedFileToDirectoryReplacement_andPreservesStaging() throws Exception {
        Files.writeString(root.resolve("node"), "committed file");
        assertEquals(0, cli("add", "node").exitCode());
        Run first = cli("commit", "-m", "initial file");
        assertEquals(0, first.exitCode(), first.output());
        String parent = Files.readString(head);
        byte[] previousSnapshot = Files.readAllBytes(snapshotPath(parent));
        Files.delete(root.resolve("node"));
        Files.createDirectory(root.resolve("node"));
        Files.writeString(root.resolve("node/child.txt"), "replacement child");
        Run add = cli("add", "node");
        assertEquals(0, add.exitCode(), add.output());
        byte[] staged = Files.readAllBytes(index);

        Run rejected = cli("commit", "-m", "unsupported replacement");

        assertEquals(1, rejected.exitCode(), rejected.output());
        assertTrue(rejected.output().contains("file/directory conflict"), rejected.output());
        assertTrue(rejected.output().contains("replacement are not supported"));
        assertFalse(rejected.output().contains("Committed snapshot"));
        assertEquals(parent, Files.readString(head));
        assertArrayEquals(staged, Files.readAllBytes(index));
        assertArrayEquals(previousSnapshot, Files.readAllBytes(snapshotPath(parent)));
        assertEquals(1, snapshotCount());
        assertFalse(Files.exists(storage.resolve("staging/commit.pending")));
    }

    private String commitParent(String path) throws Exception {
        new StagingArea(storage).stage(Path.of(path), hash);
        return new CommitService(storage).commit("valid parent").snapshotId();
    }

    private Path snapshotPath(String id) {
        return storage.resolve("snapshots").resolve(id + ".snapshot");
    }

    private long snapshotCount() throws IOException {
        try (var files = Files.list(storage.resolve("snapshots"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".snapshot")).count();
        }
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
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI did not complete");
            return new Run(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private record Run(int exitCode, String output) {
    }
}
