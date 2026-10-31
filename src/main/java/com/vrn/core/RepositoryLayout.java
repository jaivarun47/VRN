package com.vrn.core;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only layout and metadata checks for one supplied storage directory.
 * Checks the active manifest's syntax/paths and optional staging/recovery metadata, not the
 * ancestry graph, all manifests, object existence or object hashes. The caller
 * must coordinate concurrent writers if a consistent multi-file view is needed.
 * Project ignore configuration is deliberately outside this storage definition.
 */
public final class RepositoryLayout {

    private RepositoryLayout() {
    }

    public static void validate(Path storageDirectory) throws IOException {
        Path storage = storageDirectory.toAbsolutePath().normalize();
        try {
            preflight(storage);
            MetadataPaths paths = new MetadataPaths(storage);
            String head = paths.readString("HEAD").strip();
            if (!head.equals("NONE")) {
                new SnapshotStore(storage).load(head);
            }

            try {
                SnapshotManifestValidator.validatePaths(MetadataCodec.decodeIndex(paths.readLines("staging/index")).keySet());
            } catch (NoSuchFileException absentIndex) { /* An index is optional. */ }
            // Inspection only; an absent record is handled by the recovery reader, without mutation.
            new CommitRecovery(storage, new AtomicFilePublisher()).hasConsumedIndex();
        } catch (Exception failure) {
            throw new IOException("Invalid or incomplete VRN repository at " + storage + ": "
                    + failure.getMessage() + "; existing contents were preserved. Explicit recovery is required.", failure);
        }
    }

    /** Format/structure gate shared by guarded APIs; no recovery, locking, graph or object hashing. */
    static void preflight(Path storage) throws IOException {
        MetadataPaths paths = new MetadataPaths(storage);
        for (String directory : List.of("objects", "snapshots", "staging")) paths.directory(directory);
        // Check all known metadata leaves before reading any of them or creating the lock.
        paths.file("config", true);
        paths.file("HEAD", true);
        paths.file("staging/index", false);
        paths.file("staging/commit.pending", false);
        paths.file("mutation.lock", false);
        validateConfig(paths.readLines("config"));
        List<String> lines = paths.readLines("HEAD");
        if (lines.size() != 1 || lines.getFirst().strip().isEmpty()) {
            throw new IOException("Invalid repository metadata: HEAD must contain one snapshot identifier or NONE");
        }
        String id = lines.getFirst().strip();
        if (!id.equals("NONE")) {
            try { MetadataCodec.validateSnapshotId(id); }
            catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
            paths.file("snapshots/" + id + ".snapshot", true);
        }
    }

    private static void validateConfig(List<String> lines) throws IOException {
        // Current config is UTF-8 key=value text. Retain unknown settings; reject ambiguous duplicates.
        Map<String, String> settings = new HashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || line.substring(0, separator).strip().isEmpty()) {
                throw new IOException("Malformed config: expected key=value");
            }
            String key = line.substring(0, separator).strip();
            if (settings.putIfAbsent(key, line.substring(separator + 1).strip()) != null) {
                throw new IOException("Malformed config: duplicate setting " + key);
            }
        }
        String version = settings.get("version");
        if (version == null || !version.matches("[0-9]+")) {
            throw new IOException("Malformed config: version must be a decimal repository format number");
        }
        if (!version.equals("1")) {
            throw new IOException("Unsupported repository format version: " + version + " (supported: 1)");
        }
    }

}
