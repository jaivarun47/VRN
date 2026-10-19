package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class StagingArea {

    private final Path storage;
    private final Path stagingFile;
    private final AtomicFilePublisher files;

    public StagingArea(Path vrnDirectory) {
        this(vrnDirectory, new AtomicFilePublisher());
    }

    StagingArea(Path vrnDirectory, AtomicFilePublisher files) {
        this.storage = vrnDirectory;
        this.files = files;
        this.stagingFile = vrnDirectory
                .resolve("staging")
                .resolve("index");
    }

    public void stage(Path path, String hash) throws Exception {
        // Staging's existing platform-separator conversion is the only spelling conversion.
        // Stored metadata is validated strictly and is never normalized during loading.
        String relativePath = path.toString().replace('\\', '/');
        SnapshotManifestValidator.validatePath(relativePath);
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            new CommitRecovery(storage, files).recover();
            Files.createDirectories(stagingFile.getParent());
            Map<String, String> entries = readAllLocked();
            entries.put(relativePath, hash);
            SnapshotManifestValidator.validatePaths(entries.keySet());
            files.replace(stagingFile, MetadataCodec.encodeIndex(entries));
        }
    }

    /**
     * Returns all staged path-to-hash mappings, accepting version 1 and
     * unambiguous legacy metadata without rewriting it.
     * Returns an empty map if the staging index does not exist yet.
     */
    public Map<String, String> readAll() throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            return readAllLocked();
        }
    }

    Map<String, String> readAllLocked() throws Exception {
        if (new CommitRecovery(storage, files).hasConsumedIndex()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> entries = MetadataCodec.decodeIndex(Files.readAllLines(stagingFile));
            SnapshotManifestValidator.validatePaths(entries.keySet());
            return entries;
        } catch (NoSuchFileException absent) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Explicitly clears staging after reconciling any interrupted commit.
     * Commit cleanup itself uses the recorded consumed-index fingerprint.
     */
    public void clear() throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            new CommitRecovery(storage, files).recover();
            files.delete(stagingFile);
        }
    }

    /** Retries interrupted cleanup without discarding fresh staging. */
    public void recover() throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            new CommitRecovery(storage, files).recover();
        }
    }
}
