package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Computes the working-tree status by comparing three states:
 * <ol>
 *   <li>Working directory — files currently on disk</li>
 *   <li>Staging index    — {@code .vrn/staging/index}</li>
 *   <li>HEAD snapshot    — latest committed snapshot</li>
 * </ol>
 *
 * <p>Reads project files and briefly acquires the repository guard for staging reads.
 * It is deliberately separate from {@code StatusCommand} so it can be
 * tested directly without running the Picocli CLI layer.
 */
public class WorkingTreeStatus {

    /** Files in staging that are not in HEAD — will be added on next commit. */
    public final List<String> stagedNewFiles;

    /** Files in staging with a different hash than HEAD — will update on next commit. */
    public final List<String> stagedModifications;

    /**
     * Files tracked (in HEAD or staging) whose on-disk content differs from
     * the staged hash (if staged) or the committed hash (if not staged).
     * These changes have NOT been staged.
     */
    public final List<String> modifiedNotStaged;

    /**
     * Files present in HEAD but absent from disk AND not in the staging index.
     * Because the staging index has no deletion sentinel, a file staged for
     * addition that is subsequently deleted from disk is NOT reported here —
     * it still appears as a staged new file (see class-level Javadoc on
     * {@code StatusCommand} for the limitation explanation).
     */
    public final List<String> deleted;

    /** On-disk files that are neither staged nor in HEAD. */
    public final List<String> untracked;

    private WorkingTreeStatus(
            List<String> stagedNewFiles,
            List<String> stagedModifications,
            List<String> modifiedNotStaged,
            List<String> deleted,
            List<String> untracked
    ) {
        this.stagedNewFiles      = Collections.unmodifiableList(stagedNewFiles);
        this.stagedModifications = Collections.unmodifiableList(stagedModifications);
        this.modifiedNotStaged   = Collections.unmodifiableList(modifiedNotStaged);
        this.deleted             = Collections.unmodifiableList(deleted);
        this.untracked           = Collections.unmodifiableList(untracked);
    }

    /** Returns {@code true} when there are no changes of any kind. */
    public boolean isClean() {
        return stagedNewFiles.isEmpty()
                && stagedModifications.isEmpty()
                && modifiedNotStaged.isEmpty()
                && deleted.isEmpty()
                && untracked.isEmpty();
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

        Path vrnDirectory = projectRoot.resolve(".vrn");

        // Load staged entries (empty map if no index file).
        StagingArea stagingArea = new StagingArea(vrnDirectory);
        Map<String, String> staged = stagingArea.readAll();

        // Load committed entries (empty map if HEAD == "NONE").
        Map<String, String> committed = loadCommitted(vrnDirectory);

        // Build the working-directory map: path-string → hash.
        // Only hashes are needed here (no bytes) — use FileHasher.hash().
        Map<String, String> workingDir = buildWorkingDirMap(projectRoot);

        // Classify paths.
        List<String> stagedNew   = new ArrayList<>();
        List<String> stagedMod   = new ArrayList<>();
        List<String> modUnstaged = new ArrayList<>();
        List<String> deleted     = new ArrayList<>();
        List<String> untracked   = new ArrayList<>();

        // Union of all known paths (staged ∪ committed).
        Map<String, Boolean> knownPaths = new HashMap<>();
        staged.keySet().forEach(p -> knownPaths.put(p, true));
        committed.keySet().forEach(p -> knownPaths.put(p, true));

        for (String path : knownPaths.keySet()) {

            boolean inStaging   = staged.containsKey(path);
            boolean inCommitted = committed.containsKey(path);
            boolean onDisk      = workingDir.containsKey(path);

            if (inStaging) {
                String stagedHash = staged.get(path);

                // ── Staged category ──────────────────────────────────────
                if (!inCommitted) {
                    stagedNew.add(path);                // new file staged for addition
                } else if (!stagedHash.equals(committed.get(path))) {
                    stagedMod.add(path);                // existing file staged with new content
                }
                // If stagedHash == committedHash the file is staged but unchanged → clean.

                // ── Modified after staging? ───────────────────────────────
                // Check whether the on-disk version differs from what was staged.
                if (onDisk && !workingDir.get(path).equals(stagedHash)) {
                    modUnstaged.add(path);
                }
                // If in staging but not on disk: we cannot represent a staged deletion yet.
                // The staged entry is still visible as "staged new file" or "staged modification",
                // which is the current limitation.

            } else {
                // Not staged — compare disk against HEAD.
                if (inCommitted) {
                    if (!onDisk) {
                        deleted.add(path);              // committed file missing from disk
                    } else if (!workingDir.get(path).equals(committed.get(path))) {
                        modUnstaged.add(path);          // committed file changed on disk, not staged
                    }
                    // else: on disk and matches HEAD — clean.
                }
            }
        }

        // Paths on disk that are neither staged nor committed.
        for (String path : workingDir.keySet()) {
            if (!staged.containsKey(path) && !committed.containsKey(path)) {
                untracked.add(path);
            }
        }

        Collections.sort(stagedNew);
        Collections.sort(stagedMod);
        Collections.sort(modUnstaged);
        Collections.sort(deleted);
        Collections.sort(untracked);

        return new WorkingTreeStatus(stagedNew, stagedMod, modUnstaged, deleted, untracked);
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Returns the file map of the HEAD snapshot, or an empty map when
     * HEAD is {@code "NONE"} (no commits yet).
     */
    private static Map<String, String> loadCommitted(Path vrnDirectory) throws Exception {
        Path headFile = vrnDirectory.resolve("HEAD");
        String headId = Files.readString(headFile).strip();

        if (headId.equals("NONE")) {
            return Collections.emptyMap();
        }

        SnapshotStore store = new SnapshotStore(vrnDirectory);
        return store.load(headId).files();
    }

    /**
     * Walks the project root and produces a forward-slash-normalised
     * relative-path → SHA-256 hash map for every non-ignored regular file.
     *
     * <p>Uses {@link VrnIgnore} loaded from {@code .vrnignore}, exactly as
     * {@link FileScanner} does, so the same exclusion rules apply.
     */
    private static Map<String, String> buildWorkingDirMap(Path projectRoot) throws Exception {
        Path ignoreFile = projectRoot.resolve(".vrnignore");
        VrnIgnore ignore = new VrnIgnore(ignoreFile);
        FileHasher hasher = new FileHasher();

        Map<String, String> result = new HashMap<>();

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            List<Path> regularFiles = paths
                    .filter(Files::isRegularFile)
                    .map(projectRoot::relativize)
                    .filter(rel -> !ignore.isIgnored(rel))
                    .toList();

            for (Path rel : regularFiles) {
                String normalized = rel.toString().replace('\\', '/');
                String hash = hasher.hash(projectRoot.resolve(rel));
                result.put(normalized, hash);
            }
        }

        return result;
    }
}
