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
 *   <li><b>Deleted</b>               – committed file absent from disk and not in staging</li>
 *   <li><b>Untracked</b>             – on disk, not ignored, not staged, not committed</li>
 *   <li><b>Nothing to commit</b>     – none of the above</li>
 * </ul>
 *
 * <h2>Deletion limitation</h2>
 * The staging index only records additions and modifications ({@code path=hash}).
 * There is no sentinel value to represent a staged deletion.  A committed file
 * deleted from disk without re-staging is reported as "deleted (not staged)".
 * Explicitly staging a deletion would require a future {@code vrn rm} command.
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

        if (!status.stagedNewFiles.isEmpty() || !status.stagedModifications.isEmpty()) {
            System.out.println("Changes staged for commit:");
            for (String p : status.stagedNewFiles)      System.out.println("  new file:     " + p);
            for (String p : status.stagedModifications) System.out.println("  modified:     " + p);
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
    }
}
