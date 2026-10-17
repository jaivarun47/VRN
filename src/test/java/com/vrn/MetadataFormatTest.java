package com.vrn;

import com.vrn.core.FileHasher;
import com.vrn.core.ObjectStore;
import com.vrn.core.Snapshot;
import com.vrn.core.SnapshotStore;
import com.vrn.core.StagingArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataFormatTest {

    private static final String ID = "snapshot-1";
    private static final long TIMESTAMP = 1_700_000_000_000L;

    @TempDir
    Path tempDir;

    private Path storage;
    private Path snapshotFile;
    private Path indexFile;
    private SnapshotStore snapshots;
    private String hash;

    @BeforeEach
    void setUpStorage() throws Exception {
        // Serialization must also work at explicitly supplied, non-.vrn locations.
        storage = tempDir.resolve("storage");
        Files.createDirectories(storage.resolve("snapshots"));
        Files.createDirectories(storage.resolve("objects"));
        Files.createDirectories(storage.resolve("staging"));
        snapshotFile = storage.resolve("snapshots/" + ID + ".snapshot");
        indexFile = storage.resolve("staging/index");
        snapshots = new SnapshotStore(storage);

        Path file = tempDir.resolve("content.txt");
        Files.writeString(file, "Real content for metadata format tests");
        FileHasher.HashedFile content = new FileHasher().hashWithBytes(file);
        hash = content.hash();
        new ObjectStore(storage).store(hash, content.bytes());
    }

    @Test
    void versionedRoundTrip_preservesUnicodeNestedPathsAndExactLineEndings() throws Exception {
        String path = "src/नमस्ते/文件=é.txt";
        String message = "日本語 😀\r\n\r\nparent=NONE\n[files]\t=\n";
        StagingArea staging = new StagingArea(storage);
        staging.stage(Path.of(path), hash);
        staging.stage(Path.of("#notes.txt"), hash);
        Map<String, String> files = Map.of(path, hash, "#notes.txt", hash);
        Snapshot original = new Snapshot(ID, "NONE", TIMESTAMP, message, files);

        snapshots.save(original);

        assertAll(
                () -> assertEquals(files, new StagingArea(storage).readAll()),
                () -> assertEquals(original, snapshots.load(ID)),
                () -> assertTrue(Files.readString(indexFile).startsWith("VRN-STAGING\t1\n")),
                () -> assertTrue(Files.readString(snapshotFile).startsWith("VRN-SNAPSHOT\t1\n"))
        );
    }

    @Test
    void legacyRead_preservesUnambiguousDataWithoutRewritingFiles() throws Exception {
        String index = "src/文件.txt=" + hash + "\n#notes.txt=" + hash + "\n";
        String snapshot = legacySnapshot("Legacy = message", "src/文件.txt=" + hash + "\n");
        Files.writeString(indexFile, index);
        Files.writeString(snapshotFile, snapshot);

        assertAll(
                () -> assertEquals(Map.of("src/文件.txt", hash, "#notes.txt", hash),
                        new StagingArea(storage).readAll()),
                () -> assertEquals(new Snapshot(ID, "NONE", TIMESTAMP, "Legacy = message",
                        Map.of("src/文件.txt", hash)), snapshots.load(ID)),
                () -> assertEquals(index, Files.readString(indexFile)),
                () -> assertEquals(snapshot, Files.readString(snapshotFile))
        );
    }

    @Test
    void stagingMutation_readsLegacyEntriesAndWritesVersionedIndex() throws Exception {
        Files.writeString(indexFile, "old.txt=" + hash + "\n");

        new StagingArea(storage).stage(Path.of("a=b.txt"), hash);

        assertEquals(Map.of("old.txt", hash, "a=b.txt", hash), new StagingArea(storage).readAll());
        assertTrue(Files.readString(indexFile).startsWith("VRN-STAGING\t1\n"));
    }

    @Test
    void readers_rejectUnsupportedVersionsAndWrongRecordTypes() throws Exception {
        snapshots.save(snapshot("message"));
        String validSnapshot = Files.readString(snapshotFile);
        new StagingArea(storage).stage(Path.of("file.txt"), hash);
        String validIndex = Files.readString(indexFile);

        for (String header : List.of("VRN-SNAPSHOT\t2", "VRN-STAGING\t1")) {
            Files.writeString(snapshotFile, validSnapshot.replace("VRN-SNAPSHOT\t1", header));
            assertRejected(() -> snapshots.load(ID), "unsupported metadata type/version");
        }
        for (String header : List.of("VRN-STAGING\t2", "VRN-SNAPSHOT\t1", "VRN-STAGING\tversion=2")) {
            Files.writeString(indexFile, validIndex.replace("VRN-STAGING\t1", header));
            assertRejected(() -> new StagingArea(storage).readAll(), "unsupported metadata type/version");
        }
    }

    @Test
    void readers_rejectDuplicatePathsInvalidReferencesAndIncorrectCounts() throws Exception {
        new StagingArea(storage).stage(Path.of("file.txt"), hash);
        String index = Files.readString(indexFile);
        String entry = Files.readAllLines(indexFile).getLast();
        Files.writeString(indexFile, index.replace("files\t1", "files\t2") + entry + "\n");
        assertRejected(() -> new StagingArea(storage).readAll(), "duplicate file path");

        Files.writeString(indexFile, index.replace("files\t1", "files\t2"));
        assertRejected(() -> new StagingArea(storage).readAll(), "file count does not match");

        Files.writeString(indexFile, index.replace(hash, "bad/reference"));
        assertRejected(() -> new StagingArea(storage).readAll(), "object reference");

        snapshots.save(snapshot("message"));
        String snapshot = Files.readString(snapshotFile);
        String snapshotEntry = Files.readAllLines(snapshotFile).getLast();
        Files.writeString(snapshotFile, snapshot.replace("files\t1", "files\t2") + snapshotEntry + "\n");
        assertRejected(() -> snapshots.load(ID), "duplicate file path");

        Files.writeString(snapshotFile, snapshot.replace(snapshotEntry, "file\t\t" + hash));
        assertRejected(() -> snapshots.load(ID), "file path must be nonempty");
    }

    @Test
    void reader_rejectsMalformedBase64NoncanonicalEncodingAndInvalidUtf8() throws Exception {
        snapshots.save(snapshot("a"));
        String valid = Files.readString(snapshotFile);

        for (String encoded : List.of("%%%", "YQ", "/w==")) {
            Files.writeString(snapshotFile, valid.replace("message\tYQ==", "message\t" + encoded));
            assertThrows(IllegalArgumentException.class, () -> snapshots.load(ID));
        }
    }

    @Test
    void reader_rejectsMissingDuplicateUnknownAndInvalidSnapshotFields() throws Exception {
        snapshots.save(snapshot("message"));
        String valid = Files.readString(snapshotFile);
        for (String malformed : List.of(
                valid.replace("parent\tNONE\n", ""),
                valid.replace("parent\tNONE", "id\t" + ID),
                valid.replace("parent\tNONE", "unknown\tNONE"),
                valid.replace("id\t" + ID, "id\tdifferent-id"),
                valid.replace("parent\tNONE", "parent\t../outside"),
                valid.replace("timestamp\t" + TIMESTAMP, "timestamp\t-1"),
                valid.replace("timestamp\t" + TIMESTAMP, "timestamp\t9223372036854775808"),
                valid.replace("files\t1", "files\t-1")
        )) {
            Files.writeString(snapshotFile, malformed);
            assertThrows(IllegalArgumentException.class, () -> snapshots.load(ID));
        }
    }

    @Test
    void snapshotStore_rejectsUnsafeOrReservedIdentifiersBeforeFileAccess() {
        for (String id : List.of("../outside", "a/b", "a\\b", "", "NONE")) {
            assertRejected(() -> snapshots.load(id), "snapshot id");
            assertRejected(() -> snapshots.save(new Snapshot(id, "NONE", TIMESTAMP, "message", Map.of())),
                    "snapshot id");
        }
    }

    @Test
    void legacyReaders_rejectAmbiguousOrMalformedRecordsRatherThanGuessing() throws Exception {
        for (String index : List.of(
                "a=b.txt=" + hash + "\n",
                "file.txt=" + hash + "\nfile.txt=" + hash + "\n",
                "file.txt=\n",
                "not-an-entry\n"
        )) {
            Files.writeString(indexFile, index);
            assertThrows(IllegalArgumentException.class, () -> new StagingArea(storage).readAll());
        }

        for (String snapshot : List.of(
                legacySnapshot("message", "#notes.txt=" + hash + "\n"),
                legacySnapshot("message", "a=b.txt=" + hash + "\n"),
                legacySnapshot("first\nsecond", ""),
                legacySnapshot("first\nparent=NONE", ""),
                legacySnapshot("message", "file.txt=" + hash + "\nfile.txt=" + hash + "\n"),
                legacySnapshot("message", "").replace("timestamp=" + TIMESTAMP + "\n", "")
        )) {
            Files.writeString(snapshotFile, snapshot);
            assertThrows(IllegalArgumentException.class, () -> snapshots.load(ID));
        }
    }

    @Test
    void invalidStagingValue_doesNotOverwriteTheExistingIndex() throws Exception {
        StagingArea staging = new StagingArea(storage);
        staging.stage(Path.of("existing.txt"), hash);
        String before = Files.readString(indexFile);

        assertRejected(() -> staging.stage(Path.of("new.txt"), "bad\nreference"), "object reference");

        assertEquals(before, Files.readString(indexFile));
        assertEquals(Map.of("existing.txt", hash), staging.readAll());
    }

    @Test
    void writer_rejectsMalformedUnicodeWithoutPublishingASnapshot() {
        assertRejected(() -> snapshots.save(snapshot("unpaired surrogate: \uD800")), "valid Unicode");
        assertFalse(Files.exists(snapshotFile));
    }

    private Snapshot snapshot(String message) {
        return new Snapshot(ID, "NONE", TIMESTAMP, message, Map.of("file.txt", hash));
    }

    private String legacySnapshot(String message, String entries) {
        return "id=" + ID + "\nparent=NONE\ntimestamp=" + TIMESTAMP
                + "\nmessage=" + message + "\n[files]\n" + entries;
    }

    private void assertRejected(org.junit.jupiter.api.function.Executable action, String reason) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action);
        assertTrue(error.getMessage().contains(reason), error.getMessage());
    }
}
