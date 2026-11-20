package com.vrn.core;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Collection;

public class StagingArea {

    private final Path storage;
    private final AtomicFilePublisher files;

    public StagingArea(Path vrnDirectory) {
        this(vrnDirectory, new AtomicFilePublisher());
    }

    StagingArea(Path vrnDirectory, AtomicFilePublisher files) {
        this.storage = vrnDirectory;
        this.files = files;
    }

    public void stage(Path path, String hash) throws Exception {
        // Staging's existing platform-separator conversion is the only spelling conversion.
        // Stored metadata is validated strictly and is never normalized during loading.
        String relativePath = path.toString().replace('\\', '/');
        SnapshotManifestValidator.validatePath(relativePath);
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            new CommitRecovery(storage, files).recover();
            Map<String, StagedOperation> entries = readOperationsLocked();
            putLocked(entries, headFiles().keySet(), path, hash);
            publish(entries);
        }
    }

    /** Merge only; the caller owns the guard and publishes the complete batch separately. */
    void putLocked(Map<String, StagedOperation> entries, Collection<String> committed,
                   Path path, String hash) throws Exception {
        String relativePath = path.toString().replace('\\', '/');
        SnapshotManifestValidator.validatePath(relativePath);
        CaseOnlyPaths.requirePut(storage.toAbsolutePath().normalize().getParent(), relativePath,
                committed, entries.keySet());
        List<String> deletions = entries.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof StagedOperation.Delete).map(Map.Entry::getKey).toList();
        if (!deletions.isEmpty()) {
            String selected = new StagingPaths(storage.toAbsolutePath().normalize().getParent(), path).match(deletions);
            if (selected != null) relativePath = selected;
        }
        entries.put(relativePath, new StagedOperation.Put(hash));
    }

    /**
     * Compatibility reader for PUT-only indexes. Rejects DELETE rather than silently
     * hiding intent; typed consumers use readOperations(). Reading does not rewrite metadata.
     */
    public Map<String, String> readAll() throws Exception {
        return putsOnly(readOperations());
    }

    Map<String, String> readAllLocked() throws Exception {
        return putsOnly(readOperationsLocked());
    }

    private static Map<String, String> putsOnly(Map<String, StagedOperation> entries) {
        Map<String, String> puts = new LinkedHashMap<>();
        for (var entry : entries.entrySet()) {
            if (!(entry.getValue() instanceof StagedOperation.Put put)) {
                throw new IllegalStateException("Index contains DELETE operations; use readOperations() to inspect all staging intent");
            }
            puts.put(entry.getKey(), put.contentHash());
        }
        return puts;
    }

    public Map<String, StagedOperation> readOperations() throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            return readOperationsLocked();
        }
    }

    Map<String, StagedOperation> readOperationsLocked() throws Exception {
        MetadataPaths paths = new MetadataPaths(storage);
        paths.file("staging/index", false);
        if (new CommitRecovery(storage, files).hasConsumedIndex()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, StagedOperation> entries = MetadataCodec.decodeOperations(paths.readLines("staging/index"));
            SnapshotManifestValidator.validatePaths(entries.keySet());
            return entries;
        } catch (NoSuchFileException absent) {
            return new LinkedHashMap<>();
        }
    }

    public enum DeletionResult { STAGED, ALREADY_STAGED, ADDITION_CANCELED }

    /** Select removal from the next snapshot; never remove working files or stored objects. */
    public DeletionResult stageDelete(Path path) throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            RepositoryLayout.validate(storage);
            new CommitRecovery(storage, files).recover();
            Map<String, StagedOperation> entries = readOperationsLocked();
            Map<String, String> head = headFiles();
            StagingPaths selection = new StagingPaths(storage.toAbsolutePath().normalize().getParent(), path);
            String committed = selection.match(head.keySet());
            String staged = selection.match(entries.keySet());
            if (committed == null) {
                if (staged == null || !(entries.get(staged) instanceof StagedOperation.Put)) {
                    throw new IllegalArgumentException("Path is not tracked in HEAD or selected as a new PUT: " + path);
                }
                entries.remove(staged);
                publish(entries);
                return DeletionResult.ADDITION_CANCELED;
            }
            if (committed.equals(staged) && entries.get(staged) instanceof StagedOperation.Delete) {
                return DeletionResult.ALREADY_STAGED;
            }
            if (staged != null) entries.remove(staged);
            entries.put(committed, new StagedOperation.Delete());
            publish(entries);
            return DeletionResult.STAGED;
        }
    }

    /** Remove one selected operation, preserving all working files. Missing selections are a no-op. */
    public boolean unstage(Path path) throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            RepositoryLayout.validate(storage);
            new CommitRecovery(storage, files).recover();
            Map<String, StagedOperation> entries = readOperationsLocked();
            String selected = new StagingPaths(storage.toAbsolutePath().normalize().getParent(), path).match(entries.keySet());
            if (selected == null) return false;
            entries.remove(selected);
            publish(entries);
            return true;
        }
    }

    private Map<String, String> headFiles() throws Exception {
        String head = new MetadataPaths(storage).readString("HEAD").strip();
        return head.equals("NONE") ? Map.of() : new SnapshotStore(storage).load(head).files();
    }

    private void publish(Map<String, StagedOperation> entries) throws Exception {
        SnapshotManifestValidator.validatePaths(entries.keySet());
        files.replace(new MetadataPaths(storage).file("staging/index", false), MetadataCodec.encodeOperations(entries));
    }

    /**
     * Explicitly clears staging after reconciling any interrupted commit.
     * Commit cleanup itself uses the recorded consumed-index fingerprint.
     */
    public void clear() throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            new CommitRecovery(storage, files).recover();
            files.delete(new MetadataPaths(storage).file("staging/index", false));
        }
    }

    /** Retries interrupted cleanup without discarding fresh staging. */
    public void recover() throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            new CommitRecovery(storage, files).recover();
        }
    }
}
