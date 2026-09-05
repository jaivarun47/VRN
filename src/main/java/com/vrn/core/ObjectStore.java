package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;

public class ObjectStore {

    private final Path objectsDirectory;

    public ObjectStore(Path vrnDirectory) {
        this.objectsDirectory = vrnDirectory.resolve("objects");
    }

    public void store(Path file, String hash) throws Exception {

        Path objectPath = objectsDirectory.resolve(hash);

        // Content with this hash already exists.
        if (Files.exists(objectPath)) {
            return;
        }

        byte[] content = Files.readAllBytes(file);

        Files.write(objectPath, content);
    }
}