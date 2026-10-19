package com.vrn.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
            requireDirectory(storage);
            for (String directory : List.of("objects", "snapshots", "staging")) {
                requireDirectory(storage.resolve(directory));
            }
            requireFile(storage.resolve("config"));
            validateConfig(storage.resolve("config"));
            requireFile(storage.resolve("HEAD"));
            List<String> headLines = Files.readAllLines(storage.resolve("HEAD"));
            if (headLines.size() != 1 || headLines.getFirst().strip().isEmpty()) {
                throw new IOException("HEAD must contain one snapshot identifier or NONE");
            }
            String head = headLines.getFirst().strip();
            if (!head.equals("NONE")) {
                MetadataCodec.validateSnapshotId(head);
                requireFile(storage.resolve("snapshots").resolve(head + ".snapshot"));
                new SnapshotStore(storage).load(head);
            }

            Path index = storage.resolve("staging/index");
            if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
                requireFile(index);
                SnapshotManifestValidator.validatePaths(MetadataCodec.decodeIndex(Files.readAllLines(index)).keySet());
            }
            Path pending = storage.resolve("staging/commit.pending");
            if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                requireFile(pending);
                // Inspect the existing recovery protocol without deleting or rewriting anything.
                new CommitRecovery(storage, new AtomicFilePublisher()).hasConsumedIndex();
            }
            Path lock = storage.resolve("mutation.lock");
            if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
                requireFile(lock);
            }
        } catch (Exception failure) {
            throw new IOException("Invalid or incomplete VRN repository at " + storage + ": "
                    + failure.getMessage() + "; existing contents were preserved. Explicit recovery is required.", failure);
        }
    }

    private static void validateConfig(Path config) throws IOException {
        // Current config is UTF-8 key=value text. Retain unknown settings; reject ambiguous duplicates.
        Map<String, String> settings = new HashMap<>();
        for (String raw : Files.readAllLines(config)) {
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

    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Required directory is missing or has the wrong type: " + path);
        }
    }

    private static void requireFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Required regular file is missing or has the wrong type: " + path);
        }
    }
}
