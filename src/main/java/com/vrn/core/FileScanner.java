package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class FileScanner {

    private final FileHasher hasher = new FileHasher();

    public List<ScannedFile> scan(Path projectRoot, Path targetPath)
            throws Exception {

        Path ignoreFile = projectRoot.resolve(".vrnignore");
        VrnIgnore ignore = new VrnIgnore(ignoreFile);

        Path target = projectRoot.resolve(targetPath).normalize();

        if (!target.startsWith(projectRoot) || !Files.exists(target)) {
            throw new IllegalArgumentException(
                    "Invalid path: " + targetPath
            );
        }

        try (Stream<Path> paths = Files.isDirectory(target)
                ? Files.walk(target)
                : Stream.of(target)) {

            return paths
                    .filter(Files::isRegularFile)
                    .map(projectRoot::relativize)
                    .filter(path -> !ignore.isIgnored(path))
                    .map(path -> {
                        try {
                            // Read the file exactly once: get both hash and bytes.
                            FileHasher.HashedFile hf =
                                    hasher.hashWithBytes(projectRoot.resolve(path));
                            return new ScannedFile(path, hf.hash(), hf.bytes());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        }
    }
}