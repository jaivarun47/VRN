package com.vrn.core;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FileScanner {

    private final FileHasher hasher;

    public FileScanner() { this(new FileHasher()); }

    FileScanner(FileHasher hasher) { this.hasher = hasher; }

    public List<ScannedFile> scan(Path projectRoot, Path targetPath)
            throws Exception {

        Path root = projectRoot.toAbsolutePath().normalize();
        return readFiles(root, ProjectFileWalker.collect(root, targetPath));
    }

    /**
     * Add's ignore rules filter untracked discovery, not existing HEAD/index selections.
     * Compatibility capture returning bytes. AddService uses metadata-only selection
     * followed by streaming object capture under its operation guard.
     */
    public List<ScannedFile> scanForAdd(Path projectRoot, Path targetPath) throws Exception {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path storage = root.resolve(".vrn");
        Set<String> known = new LinkedHashSet<>();
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            RepositoryLayout.validate(storage);
            String head = new MetadataPaths(storage).readString("HEAD").strip();
            if (!head.equals("NONE")) known.addAll(new SnapshotStore(storage).load(head).files().keySet());
            known.addAll(new StagingArea(storage).readOperationsLocked().keySet());
            return scanForAddLocked(root, targetPath, known);
        }
    }

    /** Caller holds the repository guard and supplies the post-recovery HEAD/index membership. */
    List<ScannedFile> scanForAddLocked(Path root, Path targetPath, Set<String> known) throws Exception {
        var selected = selectForAddLocked(root, targetPath, known);
        Map<String, Path> recorded = new LinkedHashMap<>();
        for (var file : selected) recorded.put(canonical(file.source()), file.path());
        return readFiles(root, selected.stream().map(SelectedFile::source).toList(), recorded);
    }

    /** Metadata-only discovery for streaming add; never retains file contents. */
    List<SelectedFile> selectForAddLocked(Path root, Path targetPath, Set<String> known) throws Exception {
        Map<String, Path> selected = new LinkedHashMap<>();
        Map<String, Path> recorded = new LinkedHashMap<>();
        // Retain all existing traversal, containment and mandatory internal-directory checks.
        for (Path path : ProjectFileWalker.collect(root, targetPath)) selected.put(canonical(path), path);
        Path target = root.toRealPath().resolve(root.relativize(root.resolve(targetPath).normalize()));
        var attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() && !attributes.isDirectory()) {
            throw new java.io.IOException("VRN support limitation: selected path is not a regular file or directory: " + targetPath);
        }
        var identity = new WorkingTreePaths(root);
        String scope = canonical(attributes.isDirectory() ? identity.directory(target) : identity.file(target));
        String prefix = scope + "/";
        for (String stored : known) {
            // Case folding only finds candidates. Existing lookup and actual spelling prove membership.
            boolean candidate = attributes.isDirectory()
                    ? scope.isEmpty() || stored.regionMatches(true, 0, prefix, 0, prefix.length())
                    : stored.equalsIgnoreCase(scope);
            if (!candidate) continue;
            Path actual = identity.trackedFile(stored);
            if (actual == null) continue; // Missing files never become implicit DELETEs.
            String path = canonical(actual);
            boolean inScope = attributes.isDirectory() ? scope.isEmpty() || path.startsWith(prefix) : path.equals(scope);
            if (inScope) {
                if (CaseOnlyPaths.fileNameChanged(stored, path)) throw CaseOnlyPaths.unsupported(stored, path);
                selected.putIfAbsent(path, actual);
                // Existing parent-directory aliases are content lookups, not recorded renames.
                // Lookup proved identity above; keep the exact HEAD/index spelling for the PUT.
                recorded.putIfAbsent(path, Path.of(stored));
            }
        }
        if (selected.isEmpty() && attributes.isRegularFile()) {
            // A regular file omitted by discovery and not matched to known intent was ignored.
            throw new IllegalArgumentException("Ignored untracked file: " + targetPath + "; no files were staged.");
        }
        SnapshotManifestValidator.validatePaths(selected.keySet());
        List<SelectedFile> result = new ArrayList<>();
        for (var entry : selected.entrySet()) {
            result.add(new SelectedFile(entry.getValue(), recorded.getOrDefault(entry.getKey(), entry.getValue()),
                    ContentCapture.observe(root, entry.getValue())));
        }
        return List.copyOf(result);
    }

    private List<ScannedFile> readFiles(Path root, List<Path> paths) throws Exception {
        return readFiles(root, paths, Map.of());
    }

    private List<ScannedFile> readFiles(Path root, List<Path> paths, Map<String, Path> recorded) throws Exception {
        List<ScannedFile> result = new ArrayList<>();
        SnapshotManifestValidator.validatePaths(paths.stream()
                .map(path -> path.toString().replace('\\', '/')).toList());
        for (Path path : paths) {
            // Read the file exactly once: get both hash and bytes.
            new WorkingTreePaths(root).file(root.resolve(path));
            FileHasher.HashedFile hf;
            try { hf = hasher.hashWithBytes(root.resolve(path)); }
            catch (Exception failure) {
                throw new java.io.IOException("Could not capture selected file '" + canonical(path) + "': " + failure.getMessage(), failure);
            }
            result.add(new ScannedFile(recorded.getOrDefault(canonical(path), path), hf.hash(), hf.bytes()));
        }
        return List.copyOf(result);
    }

    private static String canonical(Path path) { return path.toString().replace('\\', '/'); }
}
