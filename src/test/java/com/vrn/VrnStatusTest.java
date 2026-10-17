package com.vrn;

import com.vrn.cli.CommitCommand;
import com.vrn.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WorkingTreeStatus}.
 *
 * <p>Each test calls {@link WorkingTreeStatus#compute(Path)} directly with
 * the {@link TempDir} as the project root.  This avoids any dependency on
 * {@code System.getProperty("user.dir")} or Picocli CLI invocation.
 *
 * <h2>Repository helpers</h2>
 * {@code initRepo()} creates the minimal {@code .vrn/} structure that
 * {@code vrn init} would produce.  {@code commit(Map)} saves a snapshot
 * and advances HEAD without touching the staging index.
 */
class VrnStatusTest {

    @TempDir
    Path tempDir;

    Path vrnDir;
    Path objectsDir;
    Path snapshotsDir;
    Path stagingDir;
    Path headFile;

    @BeforeEach
    void setUp() throws Exception {
        vrnDir       = tempDir.resolve(".vrn");
        objectsDir   = vrnDir.resolve("objects");
        snapshotsDir = vrnDir.resolve("snapshots");
        stagingDir   = vrnDir.resolve("staging");
        headFile     = vrnDir.resolve("HEAD");
    }

    // ======================================================================
    // 1. Fresh initialized repository — no commits, no staged files
    // ======================================================================

    @Test
    void freshRepo_noCommitsNoStaging_isClean() throws Exception {
        initRepo();

        // .vrnignore is a trackable user file (like .gitignore).
        // Commit it so it doesn't appear as untracked.
        Path ignoreFile = tempDir.resolve(".vrnignore");
        String ignoreHash = new FileHasher().hash(ignoreFile);
        commit(Map.of(".vrnignore", ignoreHash));

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.isClean(),
                "Fresh repo should be clean. stagedNew=" + status.stagedNewFiles
                + " untracked=" + status.untracked);
    }

    // ======================================================================
    // 2. Clean committed project — disk matches HEAD, nothing staged
    // ======================================================================

    @Test
    void cleanCommittedProject_isClean() throws Exception {
        initRepo();

        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "hello");
        String hashFile   = new FileHasher().hash(file);
        // Also commit .vrnignore so it is tracked, not untracked.
        String hashIgnore = new FileHasher().hash(tempDir.resolve(".vrnignore"));
        commit(Map.of("hello.txt", hashFile, ".vrnignore", hashIgnore));

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.isClean(),
                "Clean committed project should have no changes. status=" + describe(status));
    }

    // ======================================================================
    // 3. Untracked file — on disk, not staged, not committed
    // ======================================================================

    @Test
    void untrackedFile_reportedAsUntracked() throws Exception {
        initRepo();

        Files.writeString(tempDir.resolve("new.txt"), "brand new");

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.untracked.contains("new.txt"),
                "new.txt should be untracked. untracked=" + status.untracked);
        assertTrue(status.stagedNewFiles.isEmpty());
        assertTrue(status.deleted.isEmpty());
        assertTrue(status.modifiedNotStaged.isEmpty());
    }

    // ======================================================================
    // 4. Modified tracked file — changed on disk, NOT staged
    // ======================================================================

    @Test
    void modifiedTrackedFile_reportedAsModifiedNotStaged() throws Exception {
        initRepo();

        Path file = tempDir.resolve("tracked.txt");
        Files.writeString(file, "original");
        String hash = new FileHasher().hash(file);
        commit(Map.of("tracked.txt", hash));

        // Modify without staging.
        Files.writeString(file, "changed");

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.modifiedNotStaged.contains("tracked.txt"),
                "tracked.txt should be modified-not-staged. modifiedNotStaged=" + status.modifiedNotStaged);
        assertTrue(status.stagedModifications.isEmpty(),
                "Should not appear in staged modifications.");
        assertTrue(status.stagedNewFiles.isEmpty());
    }

    // ======================================================================
    // 5. Staged new file — not previously committed
    // ======================================================================

    @Test
    void stagedNewFile_reportedAsStagedNew() throws Exception {
        initRepo();

        StagingArea staging = new StagingArea(vrnDir);
        staging.stage(Path.of("brand-new.txt"), "fakehash123");

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.stagedNewFiles.contains("brand-new.txt"),
                "brand-new.txt should be staged-new. stagedNew=" + status.stagedNewFiles);
        assertTrue(status.stagedModifications.isEmpty());
        assertFalse(status.isClean());
    }

    // ======================================================================
    // 6. Staged modification — file committed, then re-staged with new hash
    // ======================================================================

    @Test
    void stagedModification_reportedAsStagedModified() throws Exception {
        initRepo();

        Path file = tempDir.resolve("mod.txt");
        Files.writeString(file, "v1");
        String hashV1 = new FileHasher().hash(file);
        commit(Map.of("mod.txt", hashV1));

        // Update and stage.
        Files.writeString(file, "v2");
        String hashV2 = new FileHasher().hash(file);
        new StagingArea(vrnDir).stage(Path.of("mod.txt"), hashV2);

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.stagedModifications.contains("mod.txt"),
                "mod.txt should be staged-mod. stagedMod=" + status.stagedModifications);
        // Disk content matches what was staged — should NOT also appear as unstaged-modified.
        assertFalse(status.modifiedNotStaged.contains("mod.txt"),
                "mod.txt should not appear in modifiedNotStaged. modifiedNotStaged=" + status.modifiedNotStaged);
        assertTrue(status.stagedNewFiles.isEmpty());
    }

    // ======================================================================
    // 7. File modified again after staging — should appear in BOTH categories
    // ======================================================================

    @Test
    void modifiedAgainAfterStaging_reportedInBothCategories() throws Exception {
        initRepo();

        Path file = tempDir.resolve("double.txt");
        Files.writeString(file, "v1");
        String hashV1 = new FileHasher().hash(file);
        commit(Map.of("double.txt", hashV1));

        // Stage v2.
        Files.writeString(file, "v2");
        String hashV2 = new FileHasher().hash(file);
        new StagingArea(vrnDir).stage(Path.of("double.txt"), hashV2);

        // Change on disk to v3 without re-staging.
        Files.writeString(file, "v3");

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.stagedModifications.contains("double.txt"),
                "double.txt should appear as staged-mod. stagedMod=" + status.stagedModifications);
        assertTrue(status.modifiedNotStaged.contains("double.txt"),
                "double.txt should also appear as modified-not-staged. modifiedNotStaged=" + status.modifiedNotStaged);
    }

    // ======================================================================
    // 8. Deleted tracked file — committed, then removed from disk, not staged
    // ======================================================================

    @Test
    void deletedTrackedFile_reportedAsDeleted() throws Exception {
        initRepo();

        Path file = tempDir.resolve("gone.txt");
        Files.writeString(file, "to be deleted");
        String hash = new FileHasher().hash(file);
        commit(Map.of("gone.txt", hash));

        Files.delete(file);

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.deleted.contains("gone.txt"),
                "gone.txt should be deleted. deleted=" + status.deleted);
        assertFalse(status.untracked.contains("gone.txt"),
                "Deleted file must not appear as untracked.");
    }

    // ======================================================================
    // 9. Missing staging index — treated same as empty staging area
    // ======================================================================

    @Test
    void missingStagingIndex_treatedAsEmptyStaging() throws Exception {
        initRepo();

        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "content");
        String hashFile   = new FileHasher().hash(file);
        // Also commit .vrnignore so it is tracked, not untracked.
        String hashIgnore = new FileHasher().hash(tempDir.resolve(".vrnignore"));
        commit(Map.of("a.txt", hashFile, ".vrnignore", hashIgnore));

        // Ensure staging index does not exist.
        Files.deleteIfExists(stagingDir.resolve("index"));

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.isClean(),
                "Clean project with no staging index should be clean. status=" + describe(status));
    }

    // ======================================================================
    // 10. Ignored files and .vrn/ internals are not reported
    // ======================================================================

    @Test
    void ignoredFiles_notReportedInAnyCategory() throws Exception {
        initRepo();

        // Add *.log to .vrnignore (it already contains ".vrn/").
        Files.writeString(tempDir.resolve(".vrnignore"), ".vrn/\n*.log\n");

        Files.writeString(tempDir.resolve("debug.log"), "log content");
        Files.writeString(vrnDir.resolve("internal.txt"), "internal");

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertFalse(status.untracked.contains("debug.log"),
                "Ignored .log file must not appear in untracked.");
        boolean anyVrn = status.untracked.stream()
                .anyMatch(p -> p.startsWith(".vrn/") || p.equals(".vrn"));
        assertFalse(anyVrn, ".vrn/ internals must not appear in untracked.");
    }

    // ======================================================================
    // 11. No commits yet, but files are staged
    // ======================================================================

    @Test
    void noCommits_stagedFile_reportedAsStagedNew() throws Exception {
        initRepo();

        new StagingArea(vrnDir).stage(Path.of("first.txt"), "abc123");

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.stagedNewFiles.contains("first.txt"),
                "Staged file before first commit should appear as staged-new. stagedNew=" + status.stagedNewFiles);
        assertTrue(status.deleted.isEmpty());
        assertTrue(status.modifiedNotStaged.isEmpty());
    }

    // ======================================================================
    // 12. Staged entry for a file that does not exist on disk
    //     (staged add, then file removed before commit — deletion limitation)
    // ======================================================================

    @Test
    void stagedEntryWithoutDiskFile_stillAppearsAsStaged() throws Exception {
        initRepo();

        // Stage a hash but never create the file on disk.
        new StagingArea(vrnDir).stage(Path.of("ghost.txt"), "somehash");

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        // The staging index has no deletion sentinel, so ghost.txt is still
        // "staged new file" even though it is not on disk.
        assertTrue(status.stagedNewFiles.contains("ghost.txt"),
                "Staged entry without on-disk file should appear as staged-new. stagedNew=" + status.stagedNewFiles);
        assertFalse(status.deleted.contains("ghost.txt"),
                "ghost.txt is in staging so it must NOT appear as deleted.");
    }

    // ======================================================================
    // 13. Multiple files — only changed ones are reported
    // ======================================================================

    @Test
    void multipleFiles_onlyChangedOnesReported() throws Exception {
        initRepo();

        Path clean  = tempDir.resolve("clean.txt");
        Path dirty  = tempDir.resolve("dirty.txt");
        Files.writeString(clean, "unchanged");
        Files.writeString(dirty, "original");

        String hashClean = new FileHasher().hash(clean);
        String hashDirty = new FileHasher().hash(dirty);
        commit(Map.of("clean.txt", hashClean, "dirty.txt", hashDirty));

        // Modify only dirty.txt.
        Files.writeString(dirty, "now changed");

        WorkingTreeStatus status = WorkingTreeStatus.compute(tempDir);

        assertTrue(status.modifiedNotStaged.contains("dirty.txt"),
                "dirty.txt should be modified. modifiedNotStaged=" + status.modifiedNotStaged);
        assertFalse(status.modifiedNotStaged.contains("clean.txt"),
                "clean.txt must not be modified. modifiedNotStaged=" + status.modifiedNotStaged);
        assertFalse(status.untracked.contains("clean.txt"),
                "clean.txt must not be untracked.");
    }

    // ======================================================================
    // 14. Regression test: .vrnignore stays tracked across subsequent commits
    // ======================================================================

    @Test
    void vrnignore_remainsTracked_afterSubsequentCommit() throws Exception {
        initRepo();

        // Step 1: Initial commit stages and commits .vrnignore
        Path ignoreFile = tempDir.resolve(".vrnignore");
        String ignoreHash = new FileHasher().hash(ignoreFile);
        StagingArea staging = new StagingArea(vrnDir);
        staging.stage(Path.of(".vrnignore"), ignoreHash);

        String firstCommitId = CommitCommand.executeCommit(tempDir, "Initial commit with .vrnignore");
        assertNotNull(firstCommitId);

        // Verify status is clean right after first commit
        WorkingTreeStatus statusAfterFirst = WorkingTreeStatus.compute(tempDir);
        assertTrue(statusAfterFirst.isClean(),
                ".vrnignore should be tracked and clean after first commit. untracked=" + statusAfterFirst.untracked);

        // Step 2: Add a second file and stage ONLY that second file
        Path secondFile = tempDir.resolve("hello.txt");
        Files.writeString(secondFile, "hello world");
        String secondHash = new FileHasher().hash(secondFile);
        staging.stage(Path.of("hello.txt"), secondHash);

        // Second commit: only hello.txt was in staging index
        String secondCommitId = CommitCommand.executeCommit(tempDir, "Second commit adding hello.txt");
        assertNotNull(secondCommitId);

        // Verify the second snapshot preserved .vrnignore from parent commit
        Snapshot secondSnapshot = new SnapshotStore(vrnDir).load(secondCommitId);
        assertTrue(secondSnapshot.files().containsKey(".vrnignore"),
                "Second snapshot must preserve .vrnignore from parent commit.");
        assertTrue(secondSnapshot.files().containsKey("hello.txt"),
                "Second snapshot must contain newly staged hello.txt.");

        // Step 3: Verify working tree status after second commit
        WorkingTreeStatus statusAfterSecond = WorkingTreeStatus.compute(tempDir);

        // .vrnignore MUST NOT be reported as untracked!
        assertFalse(statusAfterSecond.untracked.contains(".vrnignore"),
                ".vrnignore must not be reported as untracked after subsequent commits!");
        assertTrue(statusAfterSecond.isClean(),
                "Repository should be completely clean after second commit. status=" + describe(statusAfterSecond));
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    /**
     * Creates the minimal {@code .vrn/} structure that {@code vrn init} produces.
     */
    private void initRepo() throws Exception {
        Files.createDirectories(objectsDir);
        Files.createDirectories(snapshotsDir);
        Files.createDirectories(stagingDir);
        Files.writeString(headFile, "NONE");
        Files.writeString(tempDir.resolve(".vrnignore"), ".vrn/\n");
    }

    /**
     * Saves a snapshot with the given file map and advances HEAD.
     * Inherits previous snapshot entries if a parent exists.
     * Does NOT clear the staging index (that is CommitCommand's job).
     */
    private void commit(Map<String, String> files) throws Exception {
        String parentId  = Files.readString(headFile).strip();
        String snapId    = "snap-" + System.nanoTime();
        Map<String, String> ordered = new LinkedHashMap<>();
        if (!parentId.equals("NONE")) {
            Snapshot parent = new SnapshotStore(vrnDir).load(parentId);
            ordered.putAll(parent.files());
        }
        ordered.putAll(files);
        Snapshot snapshot = new Snapshot(snapId, parentId, System.currentTimeMillis(), "test commit", ordered);
        new SnapshotStore(vrnDir).save(snapshot);
        Files.writeString(headFile, snapId);
    }

    /** Returns a readable summary of a status object for assertion messages. */
    private String describe(WorkingTreeStatus s) {
        return "stagedNew=" + s.stagedNewFiles
                + " stagedMod=" + s.stagedModifications
                + " modUnstaged=" + s.modifiedNotStaged
                + " deleted=" + s.deleted
                + " untracked=" + s.untracked;
    }
}
