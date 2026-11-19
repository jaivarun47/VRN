package com.vrn.cli;

import com.vrn.core.StagingArea;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "stage-delete", description = "Exclude one tracked file from the next snapshot; keep working files")
public class StageDeleteCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Repository-relative file path")
    private Path path;

    @Override public Integer call() {
        try {
            var result = new StagingArea(Path.of(".").toAbsolutePath().normalize().resolve(".vrn")).stageDelete(path);
            System.out.println(switch (result) {
                case STAGED -> "Deletion staged; working files were preserved.";
                case ALREADY_STAGED -> "Deletion is already staged; working files were preserved.";
                case ADDITION_CANCELED -> "Staged addition canceled; working files were preserved.";
            });
            return 0;
        } catch (Exception failure) {
            System.err.println("Failed to stage deletion: " + failure.getMessage());
            return 1;
        }
    }
}
