package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;

public class ObjectStore {

    private final Path objectsDirectory;
    private final Path storage;

    public ObjectStore(Path vrnDirectory) {
        this.storage = vrnDirectory;
        this.objectsDirectory = vrnDirectory.resolve("objects");
    }

    /**
     * Stores raw file bytes under their SHA-256 hash name.
     *
     * <p>If an object with this hash already exists the write is skipped
     * (content-addressable deduplication).
     *
     * <p>The write goes to a temporary file first and is then renamed
     * atomically. This provides atomic visibility, not power-loss durability.
     */
    public void store(String hash, byte[] content) throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            Path objectPath = objectsDirectory.resolve(hash);
            if (Files.exists(objectPath)) {
                return;
            }
            new AtomicFilePublisher().replace(objectPath, content);
        }
    }
}
