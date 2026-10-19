package com.vrn.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FileScanner {

    private final FileHasher hasher = new FileHasher();

    public List<ScannedFile> scan(Path projectRoot, Path targetPath)
            throws Exception {

        Path root = projectRoot.toAbsolutePath().normalize();
        List<ScannedFile> result = new ArrayList<>();
        List<Path> paths = ProjectFileWalker.collect(root, targetPath);
        SnapshotManifestValidator.validatePaths(paths.stream()
                .map(path -> path.toString().replace('\\', '/')).toList());
        for (Path path : paths) {
            // Read the file exactly once: get both hash and bytes.
            FileHasher.HashedFile hf = hasher.hashWithBytes(root.resolve(path));
            result.add(new ScannedFile(path, hf.hash(), hf.bytes()));
        }
        return List.copyOf(result);
    }
}
