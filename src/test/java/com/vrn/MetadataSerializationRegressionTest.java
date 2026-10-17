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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Intended round-trip behavior for known metadata serialization defects.
 * These assertions remain failing until production serialization is repaired.
 */
class MetadataSerializationRegressionTest {

    private static final String SNAPSHOT_ID = "11111111111111111111111111111111";
    private static final String PARENT_ID = "22222222222222222222222222222222";
    private static final long TIMESTAMP = 1_700_000_000_000L;

    @TempDir
    Path tempDir;

    private Path vrnDirectory;
    private SnapshotStore snapshotStore;

    @BeforeEach
    void setUpStorage() throws Exception {
        vrnDirectory = tempDir.resolve(".vrn");
        Files.createDirectories(vrnDirectory.resolve("objects"));
        Files.createDirectories(vrnDirectory.resolve("snapshots"));
        snapshotStore = new SnapshotStore(vrnDirectory);
    }

    @Test
    void staging_roundTrip_preservesEqualsSignInFilenameAndHash() throws Exception {
        String path = "a=b.txt";
        String hash = storeFile(path, "Content for the equals-sign filename");
        StagingArea staging = new StagingArea(vrnDirectory);

        staging.stage(Path.of(path), hash);

        Map<String, String> loaded = new StagingArea(vrnDirectory).readAll();
        assertAll("Staging must preserve the complete filename and its associated hash",
                () -> assertEquals(Set.of(path), loaded.keySet(),
                        "The complete filename must remain the staging key"),
                () -> assertEquals(hash, loaded.get(path),
                        "The original hash must remain associated with the complete filename")
        );
    }

    @Test
    void snapshot_roundTrip_preservesFilenameBeginningWithHashSymbol() throws Exception {
        String path = "#notes.txt";
        String hash = storeFile(path, "Notes beginning with a hash-symbol filename");
        Snapshot original = new Snapshot(
                SNAPSHOT_ID, "NONE", TIMESTAMP, "Save notes", Map.of(path, hash)
        );

        snapshotStore.save(original);
        Snapshot loaded = snapshotStore.load(SNAPSHOT_ID);

        assertEquals(original.files(), loaded.files(),
                "A filename beginning with # must remain a file entry with its exact hash");
    }

    @Test
    void snapshot_roundTrip_preservesCompleteMultilineMessage() throws Exception {
        String hash = storeFile("tracked.txt", "Content for a multiline message");
        String message = "First line\n\nThird line\n";
        Snapshot original = new Snapshot(
                SNAPSHOT_ID, "NONE", TIMESTAMP, message, Map.of("tracked.txt", hash)
        );

        snapshotStore.save(original);
        Snapshot loaded = snapshotStore.load(SNAPSHOT_ID);

        assertEquals(message, loaded.message(),
                "Commit messages must preserve all lines, blank lines, and the trailing newline");
    }

    @Test
    void snapshot_roundTrip_keepsMetadataLikeMessageTextSeparateFromParent() throws Exception {
        String hash = storeFile("tracked.txt", "Content for a metadata-like message");
        Map<String, String> files = Map.of("tracked.txt", hash);
        Snapshot parent = new Snapshot(
                PARENT_ID, "NONE", TIMESTAMP - 1_000L, "Parent snapshot", files
        );
        snapshotStore.save(parent);

        String message = "Describe parent metadata\nparent=NONE\nKeep this as message text";
        Snapshot original = new Snapshot(
                SNAPSHOT_ID, PARENT_ID, TIMESTAMP, message, files
        );

        snapshotStore.save(original);
        Snapshot loaded = snapshotStore.load(SNAPSHOT_ID);

        assertAll("Metadata-like message text must remain part of the message",
                () -> assertEquals(PARENT_ID, loaded.parentId(),
                        "Message text must not replace the snapshot's actual parent identifier"),
                () -> assertEquals(message, loaded.message(),
                        "The complete metadata-like message must survive the round trip")
        );
    }

    private String storeFile(String filename, String content) throws Exception {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, content);
        FileHasher.HashedFile hashedFile = new FileHasher().hashWithBytes(file);
        new ObjectStore(vrnDirectory).store(hashedFile.hash(), hashedFile.bytes());
        return hashedFile.hash();
    }
}
