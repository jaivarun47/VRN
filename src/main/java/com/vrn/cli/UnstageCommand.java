package com.vrn.cli;

import com.vrn.core.StagingArea;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "unstage", description = "Remove one staging selection; keep working files")
public class UnstageCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Repository-relative file path")
    private Path path;

    @Override public Integer call() {
        try {
            boolean removed = new StagingArea(Path.of(".").toAbsolutePath().normalize().resolve(".vrn")).unstage(path);
            System.out.println(removed ? "Selection unstaged; working files were preserved."
                    : "No staged operation for this path; nothing changed.");
            return 0;
        } catch (Exception failure) {
            System.err.println("Failed to unstage selection: " + failure.getMessage());
            return 1;
        }
    }
}
