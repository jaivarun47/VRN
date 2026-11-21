package com.vrn.cli;

import com.vrn.core.WorkingTreeStatus;
import picocli.CommandLine.Command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code vrn status}
 *
 * <p>Shows the working tree status by comparing the working directory,
 * staging index, and the latest committed snapshot.
 *
 * <p>All comparison logic lives in {@link WorkingTreeStatus} so it can
 * be unit-tested independently of the Picocli CLI layer.
 *
 * <h2>Output categories</h2>
 * <ul>
 *   <li><b>Staged new file</b>       – in staging, NOT in HEAD snapshot</li>
 *   <li><b>Staged modification</b>   – in staging AND in HEAD, hashes differ</li>
 *   <li><b>Modified (not staged)</b> – tracked file changed on disk but not staged</li>
 *   <li><b>Staged deletion</b>       – selected absence in the next snapshot</li>
 *   <li><b>Deleted</b>               – selected content absent from disk, without a staged DELETE</li>
 *   <li><b>Untracked</b>             – newly discovered files, or retained files selected for deletion</li>
 *   <li><b>Nothing to commit</b>     – none of the above</li>
 * </ul>
 *
 * <p>DELETE selects absence. A retained working file is also untracked relative to
 * that selection; an absent file is not double-counted as an unstaged deletion.
 */
@Command(
        name = "status",
        description = "Show the working tree status"
)
public class StatusCommand implements Callable<Integer> {

    @Override
    public Integer call() {

        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        Path vrnDirectory = projectRoot.resolve(".vrn");

        try {
            if (!Files.isDirectory(vrnDirectory)) {
                System.err.println("Not a VRN repository.");
                return 1;
            }

            WorkingTreeStatus status = WorkingTreeStatus.compute(projectRoot);
            printStatus(status);
            return 0;

        } catch (Exception e) {
            System.err.println("Failed to get status: " + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------
    // Package-private so tests can invoke this without the CLI plumbing
    // ------------------------------------------------------------------

    static void printStatus(WorkingTreeStatus status) {

        if (status.isClean()) {
            System.out.println("Nothing to commit, working directory clean.");
            return;
        }

        if (!status.stagedNewFiles.isEmpty() || !status.stagedModifications.isEmpty() || !status.stagedDeletions.isEmpty()) {
            System.out.println("Changes staged for commit:");
            for (String p : status.stagedNewFiles)      System.out.println("  new file:     " + p);
            for (String p : status.stagedModifications) System.out.println("  modified:     " + p);
            for (String p : status.stagedDeletions)     System.out.println("  deleted:      " + p);
            System.out.println();
        }

        if (!status.modifiedNotStaged.isEmpty() || !status.deleted.isEmpty()) {
            System.out.println("Changes not staged for commit:");
            for (String p : status.modifiedNotStaged) System.out.println("  modified:     " + p);
            for (String p : status.deleted)           System.out.println("  deleted:      " + p);
            System.out.println();
        }

        if (!status.untracked.isEmpty()) {
            System.out.println("Untracked files:");
            for (String p : status.untracked) System.out.println("  " + p);
            System.out.println();
        }

        if (!status.caseOnlyChanges.isEmpty()) {
            System.out.println("Case-only path changes not staged:");
            for (String change : status.caseOnlyChanges) System.out.println("  " + change);
            System.out.println("Use add on the changed file or directory (or an ancestor) to stage the complete transition.");
        }
    }
}
