package com.vrn.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Shared project-file traversal for add and status; internal storage is never project content. */
final class ProjectFileWalker extends SimpleFileVisitor<Path> {

    private final Path root;
    private final VrnIgnore ignore;
    private final WorkingTreePaths identity;
    private final List<Path> files = new ArrayList<>();

    ProjectFileWalker(Path projectRoot, VrnIgnore ignore) throws IOException {
        this.root = projectRoot.toAbsolutePath().normalize();
        this.ignore = ignore;
        this.identity = new WorkingTreePaths(this.root);
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

        // Establish actual containment before relativizing: Windows lexical equality
        // alone can collapse a distinct case-sensitive sibling into this root.
        Path suppliedEnclosing = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? target : target.getParent();
        new WorkingTreePaths(root).directory(suppliedEnclosing);
        // A caller-selected project alias is an anchor, unlike an internal metadata alias.
        Path realRoot = root.toRealPath();
        target = realRoot.resolve(root.relativize(target));
        root = realRoot;

        ProjectFileWalker walker = new ProjectFileWalker(root, new VrnIgnore(root.resolve(".vrnignore")));
        // Check the actual enclosing directory even for ignored explicit targets.
        // A case-insensitive lexical prefix alone can admit a distinct sensitive sibling.
        Path enclosing = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? target : target.getParent();
        Path resolved = walker.identity.directory(enclosing);
        if (isReservedRelative(resolved)) {
            throw new IllegalArgumentException("Cannot add reserved internal repository path: " + targetPath);
        }
        Files.walkFileTree(target, walker);
        return List.copyOf(walker.files);
    }

    private static boolean isInternal(Path root, Path path) {
        // Path comparisons respect the platform's separators and path case semantics.
        // Only these root-level paths are reserved; src/.vrn remains an ordinary project path.
        return path.startsWith(root.resolve(".vrn")) || path.startsWith(root.resolve(".vrnauto"));
    }

    @Override
    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
        if (isInternal(root, directory)) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            if (ignore.isIgnored(root.relativize(directory))) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            throw unsupportedLink(directory);
        }
        Path relative = identity.directory(directory);
        if (isReservedRelative(relative)) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
        if (isInternal(root, file) || ignore.isIgnored(root.relativize(file))) {
            return FileVisitResult.CONTINUE;
        }
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw unsupportedLink(file);
        }
        if (attributes.isRegularFile()) {
            Path relative = identity.file(file);
            if (isReservedRelative(relative)) {
                throw new IOException("Unsafe repository boundary: reserved internal repository path: " + file);
            }
            // An explicit differently-cased lookup must not bypass an ignore rule
            // that matches the actual directory-entry spelling.
            if (!ignore.isIgnored(relative)) {
                files.add(relative);
            }
        }
        return FileVisitResult.CONTINUE;
    }

    private static boolean isReservedRelative(Path relative) {
        String first = relative.getName(0).toString();
        return first.equalsIgnoreCase(".vrn") || first.equalsIgnoreCase(".vrnauto");
    }

    private static IOException unsupportedLink(Path path) {
        return new IOException("VRN support limitation: symbolic links and reparse entries are not supported: " + path);
    }
}
