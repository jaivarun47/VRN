package com.vrn.core;

import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Computes the working-tree status by comparing three states:
 * <ol>
 *   <li>Working directory — files currently on disk</li>
 *   <li>Staging index    — {@code .vrn/staging/index}</li>
 *   <li>HEAD snapshot    — latest committed snapshot</li>
 * </ol>
 *
 * <p>Captures HEAD/index together under the guard, scans without holding it, and
 * checks the captured metadata again before returning. External file edits are
 * not frozen; an observed cooperating metadata change requires a retry.
 * It is deliberately separate from {@code StatusCommand} so it can be
 * tested directly without running the Picocli CLI layer.
 */
public class WorkingTreeStatus {

    /** Files in staging that are not in HEAD — will be added on next commit. */
    public final List<String> stagedNewFiles;

    /** Files in staging with a different hash than HEAD — will update on next commit. */
    public final List<String> stagedModifications;

    /** Paths explicitly selected for absence in the next snapshot. */
    public final List<String> stagedDeletions;

    /**
     * Files tracked (in HEAD or staging) whose on-disk content differs from
     * the staged hash (if staged) or the committed hash (if not staged).
     * These changes have NOT been staged.
     */
    public final List<String> modifiedNotStaged;

    /**
     * HEAD or staged regular files absent from the working tree. This working
     * deletion is independent of staged changes and is not a staged deletion.
     */
    public final List<String> deleted;

    /** Newly discovered files and retained working files whose staged selection is DELETE. */
    public final List<String> untracked;

    /** Unsupported working filename changes or existing staged spelling transitions. */
    public final List<String> caseOnlyChanges;

    private WorkingTreeStatus(
            List<String> stagedNewFiles,
            List<String> stagedModifications,
            List<String> stagedDeletions,
            List<String> modifiedNotStaged,
            List<String> deleted,
            List<String> untracked,
            List<String> caseOnlyChanges
    ) {
        this.stagedNewFiles      = Collections.unmodifiableList(stagedNewFiles);
        this.stagedModifications = Collections.unmodifiableList(stagedModifications);
        this.stagedDeletions     = Collections.unmodifiableList(stagedDeletions);
        this.modifiedNotStaged   = Collections.unmodifiableList(modifiedNotStaged);
        this.deleted             = Collections.unmodifiableList(deleted);
        this.untracked           = Collections.unmodifiableList(untracked);
        this.caseOnlyChanges     = Collections.unmodifiableList(caseOnlyChanges);
    }

    /** Returns {@code true} when there are no changes of any kind. */
    public boolean isClean() {
        return stagedNewFiles.isEmpty()
                && stagedModifications.isEmpty()
                && stagedDeletions.isEmpty()
                && modifiedNotStaged.isEmpty()
                && deleted.isEmpty()
                && untracked.isEmpty()
                && caseOnlyChanges.isEmpty();
    }

    // ------------------------------------------------------------------
    // Factory
    // ------------------------------------------------------------------

    /**
     * Computes the working-tree status for the repository rooted at
     * {@code projectRoot}.
     *
     * @param projectRoot absolute path to the project root (contains {@code .vrn/})
     * @return an immutable {@link WorkingTreeStatus}
     * @throws Exception if the repository is malformed or files cannot be read
     */
    public static WorkingTreeStatus compute(Path projectRoot) throws Exception {
        return compute(projectRoot, new FileHasher());
    }

    // Narrow seam for deterministic read failures and metadata interleavings.
    static WorkingTreeStatus compute(Path projectRoot, FileHasher hasher) throws Exception {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path storage = root.resolve(".vrn");
        Metadata captured = capture(storage);
        var identity = new WorkingTreePaths(root);
        Set<String> storedPaths = new TreeSet<>(captured.committed().keySet());
        storedPaths.addAll(captured.staged().keySet());
        Map<String, String> destinations = new HashMap<>();
        Map<String, Path> workingPaths = new HashMap<>();
        Set<String> caseChanges = new TreeSet<>();
        for (String stored : storedPaths) {
            Path actual = identity.trackedFile(stored);
            String destination = actual == null ? stored : canonical(actual);
            destinations.put(stored, destination);
            if (actual != null) workingPaths.put(destination, actual);
            if (actual != null && !(captured.staged().get(stored) instanceof StagedOperation.Delete)
                    && CaseOnlyPaths.fileNameChanged(stored, destination)) {
                caseChanges.add("working filename: " + stored + " -> " + destination);
            }
        }
        for (var entry : captured.staged().entrySet()) {
            if (entry.getValue() instanceof StagedOperation.Put) {
                String alternate = CaseOnlyPaths.alternateSpelling(entry.getKey(), captured.committed().keySet());
                if (alternate != null) caseChanges.add("staged path: " + alternate + " -> " + entry.getKey());
            }
        }
        Map<String, StagedOperation> staged = byDestination(captured.staged(), destinations);
        Map<String, String> committed = byDestination(captured.committed(), destinations);
        Map<String, String> display = new HashMap<>();
        captured.committed().keySet().forEach(path -> display.put(destinations.get(path), path));
        captured.staged().keySet().forEach(path -> display.put(destinations.get(path), path));
        // Case folding detects unsupported collisions; it never establishes identity.
        var aliases = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (String path : display.keySet()) {
            String previous = aliases.putIfAbsent(path, path);
            if (previous != null && !previous.equals(path)) {
                throw new IOException("VRN support limitation: cannot reconcile distinct selected destinations '"
                        + previous + "' and '" + path + "' without an unambiguous existing file");
            }
        }
        // Ignore rules apply to discovery, not to the tracked destinations resolved above.
        for (Path path : ProjectFileWalker.collect(root, Path.of("."))) workingPaths.put(canonical(path), path);
        SnapshotManifestValidator.validatePaths(workingPaths.keySet());
        Map<String, String> workingDir = new HashMap<>();
        for (var entry : workingPaths.entrySet()) workingDir.put(entry.getKey(), hasher.hash(root.resolve(entry.getValue())));
        if (!captured.equals(capture(storage))) {
            throw new IOException("Repository metadata changed during status; retry the operation.");
        }

        // Classify paths.
        List<String> stagedNew   = new ArrayList<>();
        List<String> stagedMod   = new ArrayList<>();
        List<String> stagedDel   = new ArrayList<>();
        List<String> modUnstaged = new ArrayList<>();
        List<String> deleted     = new ArrayList<>();
        List<String> untracked   = new ArrayList<>();

        // Union of all known paths (staged ∪ committed).
        Set<String> knownPaths = new TreeSet<>(staged.keySet());
        knownPaths.addAll(committed.keySet());

        for (String path : knownPaths) {

            boolean inStaging   = staged.containsKey(path);
            boolean inCommitted = committed.containsKey(path);
            boolean onDisk      = workingDir.containsKey(path);

            if (staged.get(path) instanceof StagedOperation.Delete) {
                stagedDel.add(display.get(path));
                // Selected absence differs from any present working file, even if ignored.
                if (onDisk) untracked.add(path);
                continue;
            }

            if (inStaging) {
                String stagedHash = ((StagedOperation.Put) staged.get(path)).contentHash();

                // ── Staged category ──────────────────────────────────────
                if (!inCommitted) {
                    stagedNew.add(display.get(path));
                } else if (!stagedHash.equals(committed.get(path))) {
                    stagedMod.add(display.get(path));
                }
            }
            // The selected content is I when present, otherwise H. Absence is independent.
            String selected = inStaging ? ((StagedOperation.Put) staged.get(path)).contentHash() : committed.get(path);
            if (!onDisk) deleted.add(display.get(path));
            else if (!workingDir.get(path).equals(selected)) modUnstaged.add(path);
        }

        // Paths on disk that are neither staged nor committed.
        for (String path : workingDir.keySet()) {
            if (!staged.containsKey(path) && !committed.containsKey(path)) {
                untracked.add(path);
            }
        }

        Collections.sort(stagedNew);
        Collections.sort(stagedMod);
        Collections.sort(stagedDel);
        Collections.sort(modUnstaged);
        Collections.sort(deleted);
        Collections.sort(untracked);

        return new WorkingTreeStatus(stagedNew, stagedMod, stagedDel, modUnstaged, deleted, untracked, new ArrayList<>(caseChanges));
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Captures HEAD's identifier, its file map, and the effective staged map
     * together. Inspection does not perform commit recovery or clear staging.
     */
    private static Metadata capture(Path storage) throws Exception {
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            RepositoryLayout.validate(storage);
            String head = new MetadataPaths(storage).readString("HEAD").strip();
            Map<String, StagedOperation> staged = new StagingArea(storage).readOperationsLocked();
            Map<String, String> committed = head.equals("NONE") ? Map.of() : new SnapshotStore(storage).load(head).files();
            for (var entry : staged.entrySet()) {
                if (entry.getValue() instanceof StagedOperation.Delete && !committed.containsKey(entry.getKey())) {
                    throw new IOException("DELETE path is not tracked in HEAD: " + entry.getKey());
                }
            }
            return new Metadata(head, Map.copyOf(staged), Map.copyOf(committed));
        }
    }

    private static <T> Map<String, T> byDestination(Map<String, T> entries, Map<String, String> destinations) throws IOException {
        Map<String, T> result = new HashMap<>();
        for (var entry : entries.entrySet()) {
            String destination = destinations.get(entry.getKey());
            if (result.put(destination, entry.getValue()) != null) {
                throw new IOException("VRN support limitation: multiple metadata entries resolve to " + destination);
            }
        }
        return result;
    }

    private static String canonical(Path relative) { return relative.toString().replace('\\', '/'); }

    private record Metadata(String head, Map<String, StagedOperation> staged, Map<String, String> committed) {}
}
