package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;

/**
 * Reads and writes snapshot metadata files in the supplied storage directory.
 *
 * <p>New files use the versioned, lossless representation in {@link MetadataCodec}.
 * A bounded subset of legacy metadata is readable without automatic migration.
 */
public class SnapshotStore {

    private final Path storage;
    private final Path snapshotsDirectory;
    private final AtomicFilePublisher files;

    public SnapshotStore(Path vrnDirectory) {
        this(vrnDirectory, new AtomicFilePublisher());
    }

    SnapshotStore(Path vrnDirectory, AtomicFilePublisher files) {
        this.storage = vrnDirectory;
        this.snapshotsDirectory = vrnDirectory.resolve("snapshots");
        this.files = files;
    }

    /**
     * Encodes the snapshot before writing, then publishes through the existing
     * temporary-file/atomic-move mechanism. This does not make HEAD or staging
     * updates transactional or establish power-loss durability.
     */
    public void save(Snapshot snapshot) throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            saveLocked(snapshot);
        }
    }

    void saveLocked(Snapshot snapshot) throws Exception {
        String content = MetadataCodec.encodeSnapshot(snapshot);
        Path finalPath = snapshotsDirectory.resolve(snapshot.id() + ".snapshot");
        if (!Files.notExists(finalPath)) {
            throw new FileAlreadyExistsException("Published snapshot already exists: " + finalPath);
        }
        files.replace(finalPath, content);
    }

    /**
     * Reads and parses a snapshot file by its ID.
     *
     * @param id the snapshot ID (without the {@code .snapshot} extension)
     * @return the parsed {@link Snapshot}
     * @throws IllegalArgumentException if the file is missing or malformed
     */
    public Snapshot load(String id) throws Exception {

        MetadataCodec.validateSnapshotId(id);
        Path snapshotPath = snapshotsDirectory.resolve(id + ".snapshot");

        if (!Files.exists(snapshotPath)) {
            throw new IllegalArgumentException(
                    "Snapshot not found: " + id
            );
        }

        return MetadataCodec.decodeSnapshot(id, Files.readAllLines(snapshotPath));
    }
}
