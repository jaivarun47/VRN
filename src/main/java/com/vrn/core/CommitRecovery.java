package com.vrn.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A single pending-cleanup record, not a general transaction log. Callers hold
 * the storage mutation guard. No staging writer may publish a new index until
 * recovery retires this record. Thus a later identical restaging is still new work.
 */
final class CommitRecovery {

    private static final String HEADER = "VRN-COMMIT-CLEANUP\t1";
    private final Path storage;
    private final Path index;
    private final Path pendingFile;
    private final AtomicFilePublisher files;

    CommitRecovery(Path storage, AtomicFilePublisher files) {
        this.storage = storage;
        this.index = storage.resolve("staging/index");
        this.pendingFile = storage.resolve("staging/commit.pending");
        this.files = files;
    }

    void prepare(String snapshotId, String parentId) throws IOException {
        files.replace(pendingFile, HEADER + "\nsnapshot\t" + snapshotId
                + "\nparent\t" + parentId + "\nindex\t" + fingerprint(index) + "\n");
    }

    /** True means any remaining index belongs to the active commit, not pending work. */
    boolean hasConsumedIndex() throws Exception {
        Pending pending = read();
        return pending != null && isPublished(pending);
    }

    void recover() throws Exception {
        Pending pending = read();
        if (pending == null) {
            return;
        }
        if (isPublished(pending)) {
            files.delete(index);
        }
        // Retire the barrier before permitting newer staging, even if bytes match.
        files.delete(pendingFile);
    }

    private boolean isPublished(Pending pending) throws Exception {
        String head = Files.readString(storage.resolve("HEAD")).strip();
        if (head.equals(pending.snapshotId())) {
            Snapshot snapshot = new SnapshotStore(storage).load(head);
            if (!snapshot.parentId().equals(pending.parentId())) {
                throw new IOException("Commit recovery parent does not match the active snapshot");
            }
            try {
                if (!fingerprint(index).equals(pending.indexHash())) {
                    throw new IOException("Commit recovery found changed staging; refusing to clear it");
                }
            } catch (NoSuchFileException alreadyCleaned) {
                // Cleanup may have deleted the index before interruption.
            }
            return true;
        }
        if (head.equals(pending.parentId())) {
            return false; // HEAD was not published; all staging remains pending.
        }
        throw new IOException("Commit recovery found an unexpected HEAD; repository state was preserved");
    }

    private Pending read() throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(pendingFile);
        } catch (NoSuchFileException absent) {
            return null;
        }
        if (lines.size() != 4 || !lines.getFirst().equals(HEADER)) {
            throw new IOException("Invalid or unsupported commit recovery record: " + pendingFile);
        }
        String snapshot = field(lines.get(1), "snapshot");
        String parent = field(lines.get(2), "parent");
        String hash = field(lines.get(3), "index");
        try {
            MetadataCodec.validateSnapshotId(snapshot);
            if (!parent.equals("NONE")) {
                MetadataCodec.validateSnapshotId(parent);
            }
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid identifier in commit recovery record", failure);
        }
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid staging fingerprint in commit recovery record");
        }
        return new Pending(snapshot, parent, hash);
    }

    private String field(String line, String name) throws IOException {
        String[] parts = line.split("\t", -1);
        if (parts.length != 2 || !parts[0].equals(name)) {
            throw new IOException("Invalid " + name + " field in commit recovery record");
        }
        return parts[1];
    }

    private String fingerprint(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by Java", failure);
        }
    }

    private record Pending(String snapshotId, String parentId, String indexHash) {
    }
}
