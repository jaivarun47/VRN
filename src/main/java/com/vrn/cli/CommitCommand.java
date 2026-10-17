package com.vrn.cli;

import com.vrn.core.CommitService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code vrn commit -m "message"}
 *
 * <p>Delegates publication and recovery to the core operation. HEAD publication
 * means success; a subsequent cleanup warning does not turn it into a failed commit.
 */
@Command(
        name = "commit",
        description = "Save staged files as a new snapshot"
)
public class CommitCommand implements Callable<Integer> {

    @Option(
            names = {"-m", "--message"},
            description = "Commit message",
            required = true
    )
    private String message;

    @Override
    public Integer call() {

        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        Path vrnDirectory = projectRoot.resolve(".vrn");

        try {
            if (!Files.isDirectory(vrnDirectory)) {
                System.err.println("Not a VRN repository.");
                return 1;
            }

            CommitService.Result result = new CommitService(vrnDirectory).commit(message);
            System.out.println("Committed snapshot " + result.snapshotId());
            printWarning(result);
            return 0;
        } catch (Exception e) {
            System.err.println("Commit operation stopped: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Compatibility entry point for callers supplying a manual project root.
     * Reusable core callers should use CommitService with an explicit storage path.
     *
     * @param projectRoot project root directory containing {@code .vrn/}
     * @param message commit message
     * @return the generated snapshot ID
     * @throws Exception if repository operations fail
     */
    public static String executeCommit(Path projectRoot, String message) throws Exception {

        CommitService.Result result = new CommitService(projectRoot.resolve(".vrn")).commit(message);
        printWarning(result);
        return result.snapshotId();
    }

    private static void printWarning(CommitService.Result result) {
        if (result.warning() != null) {
            System.err.println("Warning: " + result.warning());
        }
    }
}
