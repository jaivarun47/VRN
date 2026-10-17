package com.vrn;

import com.vrn.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit / integration tests for the VRN commit milestone.
 *
 * Each test gets a fresh {@code @TempDir} so tests never share state.
 */
class VrnCommitTest {

    @TempDir
    Path tempDir;

    // Subdirectories that mimic what vrn init creates.
    Path vrnDir;
    Path objectsDir;
    Path snapshotsDir;
    Path stagingDir;
    Path headFile;

    @BeforeEach
    void setUpRepository() throws Exception {
        vrnDir       = tempDir.resolve(".vrn");
        objectsDir   = vrnDir.resolve("objects");
        snapshotsDir = vrnDir.resolve("snapshots");
        stagingDir   = vrnDir.resolve("staging");
        headFile     = vrnDir.resolve("HEAD");

        Files.createDirectories(objectsDir);
        Files.createDirectories(snapshotsDir);
        Files.createDirectories(stagingDir);
        Files.writeString(headFile, "NONE");

        // Minimal .vrnignore so FileScanner does not throw.
        Files.writeString(tempDir.resolve(".vrnignore"), ".vrn/\n");
    }

    // ------------------------------------------------------------------
    // FileHasher
    // ------------------------------------------------------------------

    @Test
    void fileHasher_hashWithBytes_returnsConsistentHashAndBytes() throws Exception {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "hello world");

        FileHasher hasher = new FileHasher();
        FileHasher.HashedFile hf = hasher.hashWithBytes(file);

        assertNotNull(hf.hash());
        assertEquals(64, hf.hash().length(), "SHA-256 hex should be 64 chars");
        assertArrayEquals(Files.readAllBytes(file), hf.bytes());

        // Hashing again must give the same result.
        assertEquals(hf.hash(), hasher.hash(file));
    }

    @Test
    void fileHasher_differentContent_producesDifferentHash() throws Exception {
        Path a = tempDir.resolve("a.txt");
        Path b = tempDir.resolve("b.txt");
        Files.writeString(a, "foo");
        Files.writeString(b, "bar");

        FileHasher hasher = new FileHasher();
        assertNotEquals(hasher.hash(a), hasher.hash(b));
    }

    // ------------------------------------------------------------------
    // ObjectStore
    // ------------------------------------------------------------------

    @Test
    void objectStore_storesContentByHash() throws Exception {
        ObjectStore store = new ObjectStore(vrnDir);
        byte[] content = "hello".getBytes();
        String hash = "abc123";

        store.store(hash, content);

        Path object = objectsDir.resolve(hash);
        assertTrue(Files.exists(object));
        assertArrayEquals(content, Files.readAllBytes(object));
    }

    @Test
    void objectStore_doesNotOverwriteExistingObject() throws Exception {
        ObjectStore store = new ObjectStore(vrnDir);
        byte[] original = "first".getBytes();
        String hash = "aabbcc";

        store.store(hash, original);

        // Second call with different bytes must be skipped.
        store.store(hash, "second".getBytes());

        assertArrayEquals(original, Files.readAllBytes(objectsDir.resolve(hash)));
    }

    // ------------------------------------------------------------------
    // StagingArea
    // ------------------------------------------------------------------

    @Test
    void stagingArea_stageAndReadAll() throws Exception {
        StagingArea staging = new StagingArea(vrnDir);
        Path relativePath = Path.of("src/Foo.java");

        staging.stage(relativePath, "deadbeef");

        Map<String, String> entries = staging.readAll();
        assertEquals(1, entries.size());
        assertEquals("deadbeef", entries.get("src/Foo.java"));
    }

    @Test
    void stagingArea_restageUpdatesHash() throws Exception {
        StagingArea staging = new StagingArea(vrnDir);
        Path relativePath = Path.of("src/Foo.java");

        staging.stage(relativePath, "hash1");
        staging.stage(relativePath, "hash2");

        Map<String, String> entries = staging.readAll();
        assertEquals(1, entries.size(), "Re-staged path should appear only once");
        assertEquals("hash2", entries.get("src/Foo.java"));
    }

    @Test
    void stagingArea_readAll_emptyWhenNoIndex() throws Exception {
        StagingArea staging = new StagingArea(vrnDir);
        assertTrue(staging.readAll().isEmpty());
    }

    @Test
    void stagingArea_clear_deletesIndex() throws Exception {
        StagingArea staging = new StagingArea(vrnDir);
        staging.stage(Path.of("src/Foo.java"), "abc");

        staging.clear();

        assertTrue(staging.readAll().isEmpty());
        assertFalse(Files.exists(stagingDir.resolve("index")));
    }

    @Test
    void stagingArea_pathSeparatorNormalized() throws Exception {
        StagingArea staging = new StagingArea(vrnDir);
        // Simulate a Windows backslash path.
        staging.stage(Path.of("src\\main\\Foo.java"), "hashX");

        Map<String, String> entries = staging.readAll();
        // Key must use forward slashes regardless of OS.
        assertTrue(entries.containsKey("src/main/Foo.java"),
                "Expected forward-slash key but got: " + entries.keySet());
    }

    // ------------------------------------------------------------------
    // SnapshotStore
    // ------------------------------------------------------------------

    @Test
    void snapshotStore_saveAndLoad_roundTrip() throws Exception {
        SnapshotStore store = new SnapshotStore(vrnDir);

        Map<String, String> files = Map.of(
                "src/Main.java", "aabbcc",
                "README.md",     "112233"
        );

        Snapshot original = new Snapshot(
                "abc123def456",
                "NONE",
                1_700_000_000_000L,
                "Initial commit",
                files
        );

        store.save(original);

        Snapshot loaded = store.load("abc123def456");

        assertEquals(original.id(),        loaded.id());
        assertEquals(original.parentId(),  loaded.parentId());
        assertEquals(original.timestamp(), loaded.timestamp());
        assertEquals(original.message(),   loaded.message());
        assertEquals(original.files(),     loaded.files());
    }

    @Test
    void snapshotStore_snapshotFileNamedCorrectly() throws Exception {
        SnapshotStore store = new SnapshotStore(vrnDir);
        Snapshot snap = new Snapshot("myid001", "NONE", 0L, "msg", Map.of());

        store.save(snap);

        assertTrue(Files.exists(snapshotsDir.resolve("myid001.snapshot")));
    }

    @Test
    void snapshotStore_loadThrowsForUnknownId() {
        SnapshotStore store = new SnapshotStore(vrnDir);
        assertThrows(Exception.class, () -> store.load("doesnotexist"));
    }

    @Test
    void snapshotStore_parentIdPreserved_forFirstCommit() throws Exception {
        SnapshotStore store = new SnapshotStore(vrnDir);
        Snapshot snap = new Snapshot("id001", "NONE", 0L, "first", Map.of());

        store.save(snap);
        Snapshot loaded = store.load("id001");

        assertEquals("NONE", loaded.parentId());
    }

    @Test
    void snapshotStore_parentIdPreserved_forSubsequentCommit() throws Exception {
        SnapshotStore store = new SnapshotStore(vrnDir);
        Snapshot snap = new Snapshot("id002", "id001", 0L, "second", Map.of());

        store.save(snap);
        Snapshot loaded = store.load("id002");

        assertEquals("id001", loaded.parentId());
    }

    // ------------------------------------------------------------------
    // FileScanner (integration — uses the real .vrnignore)
    // ------------------------------------------------------------------

    @Test
    void fileScanner_scansFileAndReturnsBytes() throws Exception {
        Path src = tempDir.resolve("hello.txt");
        Files.writeString(src, "hello VRN");

        FileScanner scanner = new FileScanner();
        var results = scanner.scan(tempDir, Path.of("hello.txt"));

        assertEquals(1, results.size());
        ScannedFile sf = results.get(0);
        assertNotNull(sf.hash());
        assertNotNull(sf.bytes());
        assertArrayEquals("hello VRN".getBytes(), sf.bytes());
    }

    @Test
    void fileScanner_ignoresVrnDirectory() throws Exception {
        // The .vrnignore already contains ".vrn/" from setUp.
        // Create a file inside .vrn — it must not appear in scan results.
        Files.writeString(vrnDir.resolve("some-internal-file.txt"), "internal");
        Files.writeString(tempDir.resolve("visible.txt"), "visible");

        FileScanner scanner = new FileScanner();
        var results = scanner.scan(tempDir, Path.of("."));

        boolean anyVrnFile = results.stream()
                .anyMatch(f -> {
                    String p = f.path().toString().replace('\\', '/');
                    return p.equals(".vrn") || p.startsWith(".vrn/");
                });
        assertFalse(anyVrnFile, ".vrn/ files should be ignored");

        boolean hasVisible = results.stream()
                .anyMatch(f -> f.path().toString().replace('\\', '/').equals("visible.txt"));
        assertTrue(hasVisible, "visible.txt should be included");
    }

    // ------------------------------------------------------------------
    // Full commit workflow (end-to-end without the CLI layer)
    // ------------------------------------------------------------------

    @Test
    void commitWorkflow_happyPath() throws Exception {
        // Set up: one staged file.
        StagingArea staging = new StagingArea(vrnDir);
        staging.stage(Path.of("src/Main.java"), "cafebabe");

        // Read HEAD (parent).
        String parentId = Files.readString(headFile).strip();
        assertEquals("NONE", parentId);

        // Build and save snapshot.
        String snapshotId = "testsnap001";
        Snapshot snap = new Snapshot(
                snapshotId,
                parentId,
                System.currentTimeMillis(),
                "Initial commit",
                staging.readAll()
        );

        SnapshotStore snapshotStore = new SnapshotStore(vrnDir);
        snapshotStore.save(snap);

        // Update HEAD.
        Files.writeString(headFile, snapshotId);

        // Clear staging.
        staging.clear();

        // Verify.
        assertEquals(snapshotId, Files.readString(headFile).strip());
        assertTrue(staging.readAll().isEmpty(), "Staging should be empty after commit");

        Snapshot loaded = snapshotStore.load(snapshotId);
        assertEquals("cafebabe", loaded.files().get("src/Main.java"));
        assertEquals("NONE", loaded.parentId());
    }

    @Test
    void commitWorkflow_secondCommitHasCorrectParent() throws Exception {
        SnapshotStore snapshotStore = new SnapshotStore(vrnDir);
        StagingArea staging = new StagingArea(vrnDir);

        // First commit.
        staging.stage(Path.of("a.txt"), "hash1");
        String firstId = "snap001";
        snapshotStore.save(new Snapshot(firstId, "NONE", 0L, "first", staging.readAll()));
        Files.writeString(headFile, firstId);
        staging.clear();

        // Second commit.
        staging.stage(Path.of("b.txt"), "hash2");
        String parent = Files.readString(headFile).strip();
        String secondId = "snap002";
        snapshotStore.save(new Snapshot(secondId, parent, 0L, "second", staging.readAll()));
        Files.writeString(headFile, secondId);
        staging.clear();

        // Verify chain.
        Snapshot second = snapshotStore.load(secondId);
        assertEquals(firstId, second.parentId());
    }
}
