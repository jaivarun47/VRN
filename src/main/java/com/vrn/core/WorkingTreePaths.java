package com.vrn.core;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One scan's resolved-root boundary and actual-spelling checks. Directory resolutions
 * are cached only for this operation. This does not discover or cache case flags,
 * compare file keys, or treat hard links as duplicate directory entries.
 */
final class WorkingTreePaths {
    private final Path realRoot;
    private final String rootPrefix;
    private final Map<String, Path> directories = new HashMap<>();
    private final Map<String, Set<String>> entryNames = new HashMap<>();

    WorkingTreePaths(Path root) throws IOException {
        realRoot = root.toRealPath();
        String spelling = realRoot.toString();
        rootPrefix = spelling.endsWith(root.getFileSystem().getSeparator()) ? spelling
                : spelling + root.getFileSystem().getSeparator();
        directories.put(root.toString(), realRoot);
    }

    Path directory(Path directory) throws IOException {
        Path real = directories.get(directory.toString());
        if (real == null) {
            real = directory.toRealPath();
            directories.put(directory.toString(), real);
        }
        return relative(real, directory);
    }

    Path file(Path file) throws IOException {
        directory(file.getParent());
        Path realParent = directories.get(file.getParent().toString());
        Set<String> names = names(realParent);
        String requested = file.getFileName().toString();
        String spelling = requested;
        if (!names.contains(requested)) {
            List<String> matches = names.stream().filter(name -> name.equalsIgnoreCase(requested)).toList();
            if (matches.size() != 1) {
                throw new IOException("VRN support limitation: cannot resolve an unambiguous directory entry for " + file);
            }
            spelling = matches.getFirst();
        }
        // Destination identity is the directory entry, not a physical file key or a hard-link target.
        return relative(realParent.resolve(spelling), file);
    }

    /**
     * Resolve one validated tracked destination independently of ignore rules.
     * A unique case candidate is used only after the provider accepts the requested
     * lookup. A case-sensitive missing spelling therefore remains missing.
     * Null means observed absence of a regular file, including a type replacement;
     * access errors, reparse entries and ambiguous component names are errors.
     */
    Path trackedFile(String stored) throws IOException {
        SnapshotManifestValidator.validatePath(stored);
        String[] parts = stored.split("/");
        Path parent = realRoot;
        for (int i = 0; i < parts.length; i++) {
            Path requested = parent.resolve(parts[i]);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(requested, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException absent) {
                return null;
            }
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("VRN support limitation: tracked symbolic/reparse entry is not supported: " + requested);
            }
            Set<String> names = names(parent);
            String component = parts[i];
            List<String> candidates = names.stream().filter(name -> name.equalsIgnoreCase(component)).toList();
            if (candidates.size() != 1) {
                throw new IOException("VRN support limitation: ambiguous or changing tracked destination: " + requested);
            }
            Path actual = parent.resolve(candidates.getFirst());
            if (i == parts.length - 1) {
                if (attributes.isDirectory()) return null;
                if (!attributes.isRegularFile()) throw new IOException("Unsupported tracked file type: " + requested);
                return relative(actual, requested);
            }
            if (!attributes.isDirectory()) return null;
            directory(actual);
            parent = directories.get(actual.toString());
        }
        throw new IOException("Invalid tracked destination: " + stored);
    }

    private Set<String> names(Path directory) throws IOException {
        Set<String> names = entryNames.get(directory.toString());
        if (names == null) {
            try (var entries = Files.newDirectoryStream(directory)) {
                var collected = new HashSet<String>();
                for (Path entry : entries) collected.add(entry.getFileName().toString());
                names = Set.copyOf(collected);
            }
            entryNames.put(directory.toString(), names);
        }
        return names;
    }

    private Path relative(Path real, Path supplied) throws IOException {
        // Windows Path.startsWith/equals ignore case even in case-sensitive directories.
        // Real paths have filesystem spelling, so compare that resolved spelling exactly.
        if (!real.toString().equals(realRoot.toString()) && !real.toString().startsWith(rootPrefix)) {
            throw new IOException("Unsafe repository boundary: resolved path is outside the project: " + supplied);
        }
        return realRoot.relativize(real);
    }
}
