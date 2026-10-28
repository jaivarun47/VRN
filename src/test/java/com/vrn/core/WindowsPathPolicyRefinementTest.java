package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Host-independent metadata policy tests; no Windows filesystem assumptions. */
class WindowsPathPolicyRefinementTest {

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {" leading.txt", " leading directory/file.txt", "del-\u007f.txt", "c1-\u0085.txt",
            "CON\u2000.txt", "CON\u00a0.txt", "\u00a0name.txt", "name.txt\u3000", "資料/😀=é.txt", "#notes.txt",
            "config=production.txt", "[]{}+!@;$&()%,.txt"})
    void supportsNamesThatJava21CanRepresentWithoutTrimming(String path) {
        SnapshotManifestValidator.validatePath(path);
        SnapshotManifestValidator.validatePaths(List.of(path));
    }

    @ParameterizedTest
    @CsvSource({"Src/A.java,src/B.java", "Src/sub/A,src/SUB/B", "SRC/a,src/a.txt"})
    void sharedDirectoryCapitalizationDoesNotMakeDifferentFilesCollide(String first, String second) {
        SnapshotManifestValidator.validatePaths(List.of(first, second));
        SnapshotManifestValidator.validatePaths(List.of(second, first));
    }

    @Test
    void ambiguousCaseEquivalentCompleteDestinationsRemainAnExplicitSupportLimit() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SnapshotManifestValidator.validatePaths(List.of("Src/A.java", "src/a.JAVA")));
        assertTrue(error.getMessage().contains("VRN support limitation"));
        assertTrue(error.getMessage().contains("case-equivalent destination collision"));
    }

    @Test
    void diagnosticsDistinguishBoundaryStructureApiAndSupportRestrictions() {
        assertReason("../escape", "unsafe repository boundary");
        assertReason("C:/escape", "unsafe repository boundary");
        assertReason(".vrn/HEAD", "reserved internal repository path");
        assertReason("a//b", "invalid snapshot structure");
        assertReason("a\\b", "invalid snapshot structure");
        assertReason("bad|name", "Windows/Java path restriction");
        assertReason("bad\u001f", "Windows/Java path restriction");
        assertReason("trailing ", "Windows/Java path restriction");
        assertReason("trailing.", "ambiguous Windows destination");
        assertReason("NUL.txt", "VRN support limitation");
        assertReason("x".repeat(256), "VRN support limitation");
        IllegalArgumentException exact = assertThrows(IllegalArgumentException.class,
                () -> SnapshotManifestValidator.validatePaths(List.of("node", "node/child")));
        assertTrue(exact.getMessage().contains("invalid snapshot structure"));
        IllegalArgumentException folded = assertThrows(IllegalArgumentException.class,
                () -> SnapshotManifestValidator.validatePaths(List.of("Node", "node/child")));
        assertTrue(folded.getMessage().contains("VRN support limitation"));
    }

    @Test
    void historicalMetadataNeedsNoOriginalFilesOrCapabilityCache() throws Exception {
        new RepositoryInitializer().initialize(root);
        Path storage = root.resolve(".vrn");
        Map<String, String> paths = Map.of("Src/A.java", "hash", "src/B.java", "hash",
                " leading.txt", "hash", "CON\u2000.txt", "hash", "del-\u007f.txt", "hash");
        Snapshot snapshot = new Snapshot("historical", "NONE", 1, "existing names", paths);
        SnapshotStore store = new SnapshotStore(storage);
        store.save(snapshot);
        Path metadata = storage.resolve("snapshots/historical.snapshot");
        byte[] before = Files.readAllBytes(metadata);
        Files.writeString(storage.resolve("HEAD"), "historical");

        assertEquals(paths, store.load("historical").files());
        RepositoryLayout.validate(storage);

        assertFalse(Files.exists(root.resolve("Src")));
        assertFalse(Files.exists(root.resolve(" leading.txt")));
        assertArrayEquals(before, Files.readAllBytes(metadata));
    }

    @Test
    void stagingAndParentMergeAllowSharedDirectorySpellings_withoutRemovingHistory() throws Exception {
        new RepositoryInitializer().initialize(root);
        Path storage = root.resolve(".vrn");
        StagingArea staging = new StagingArea(storage);
        staging.stage(Path.of("Src/A.java"), "first-hash");
        String first = new CommitService(storage).commit("first spelling").snapshotId();
        staging.stage(Path.of("src/B.java"), "second-hash");

        String second = new CommitService(storage).commit("another distinct file").snapshotId();

        Snapshot loaded = new SnapshotStore(storage).load(second);
        assertEquals(first, loaded.parentId());
        assertEquals(Map.of("Src/A.java", "first-hash", "src/B.java", "second-hash"), loaded.files());
        assertTrue(staging.readAll().isEmpty());
    }

    private void assertReason(String path, String category) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SnapshotManifestValidator.validatePath(path));
        assertTrue(error.getMessage().contains(category), error.getMessage());
    }
}
