package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;

/**
 * Reads and writes snapshot metadata files in the supplied storage directory.
 *
 * <p>New files use the versioned, lossless representation in {@link MetadataCodec}.
 * A bounded subset of legacy metadata is readable without automatic migration.
 * This is a metadata store, not a content-integrity audit. CommitService verifies
 * all candidate objects before calling saveLocked and advancing HEAD; metadata
 * inspection remains available even when historical objects are damaged.
 */
public class SnapshotStore {

    private final Path storage;
    private final AtomicFilePublisher files;

    public SnapshotStore(Path vrnDirectory) {
        this(vrnDirectory, new AtomicFilePublisher());
    }

    SnapshotStore(Path vrnDirectory, AtomicFilePublisher files) {
        this.storage = vrnDirectory;
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
        // This also guards CommitService's complete parent-plus-staging candidate before any publication.
        SnapshotManifestValidator.validatePaths(snapshot.files().keySet());
        Path finalPath = new MetadataPaths(storage).file("snapshots/" + snapshot.id() + ".snapshot", false);
        if (!Files.notExists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
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
        RepositoryLayout.preflight(storage);
        Path snapshotPath = new MetadataPaths(storage).file("snapshots/" + id + ".snapshot", false);

        if (!Files.exists(snapshotPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "Snapshot not found: " + id
            );
        }

        Snapshot snapshot = MetadataCodec.decodeSnapshot(id, new MetadataPaths(storage).readLines("snapshots/" + id + ".snapshot"));
        SnapshotManifestValidator.validatePaths(snapshot.files().keySet());
        return snapshot;
    }
}
