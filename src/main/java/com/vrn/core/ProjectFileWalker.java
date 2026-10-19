package com.vrn.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Shared project-file traversal for add and status; internal storage is never project content. */
final class ProjectFileWalker extends SimpleFileVisitor<Path> {

    private final Path root;
    private final VrnIgnore ignore;
    private final List<Path> files = new ArrayList<>();

    ProjectFileWalker(Path projectRoot, VrnIgnore ignore) {
        this.root = projectRoot.toAbsolutePath().normalize();
        this.ignore = ignore;
    }

    static List<Path> collect(Path projectRoot, Path targetPath) throws Exception {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path target = root.resolve(targetPath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid path: " + targetPath);
        }
        // Check before loading ignore rules, including when the ignore file is missing.
        if (isInternal(root, target)) {
            throw new IllegalArgumentException("Cannot add reserved internal repository path: " + targetPath);
        }
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Invalid path: " + targetPath);
        }

        ProjectFileWalker walker = new ProjectFileWalker(root, new VrnIgnore(root.resolve(".vrnignore")));
        Files.walkFileTree(target, walker);
        return List.copyOf(walker.files);
    }

    private static boolean isInternal(Path root, Path path) {
        // Path comparisons respect the platform's separators and path case semantics.
        // Only these root-level paths are reserved; src/.vrn remains an ordinary project path.
        return path.startsWith(root.resolve(".vrn")) || path.startsWith(root.resolve(".vrnauto"));
    }

    @Override
    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
        return isInternal(root, directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
        // Keep the existing regular-file check (including file links); do not follow directory links.
        if (!isInternal(root, file) && Files.isRegularFile(file)) {
            Path relative = root.relativize(file);
            if (!ignore.isIgnored(relative)) {
                files.add(relative);
            }
        }
        return FileVisitResult.CONTINUE;
    }
}
