package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class FileScanner {

    private final FileHasher hasher = new FileHasher();

    public List<ScannedFile> scan(Path projectRoot) throws Exception {

        Path ignoreFile = projectRoot.resolve(".vrnignore");
        VrnIgnore ignore = new VrnIgnore(ignoreFile);

        try (Stream<Path> paths = Files.walk(projectRoot)) {

            return paths
                    .filter(Files::isRegularFile)
                    .map(projectRoot::relativize)
                    .filter(path -> !ignore.isIgnored(path))
                    .map(path -> {
                        try {
                            String hash = hasher.hash(projectRoot.resolve(path));
                            return new ScannedFile(path, hash);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        }
    }
}