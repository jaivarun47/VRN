package com.vrn.cli;

import com.vrn.core.AddService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "add",
        description = "Stage files for the next snapshot"
)
public class AddCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            description = "File or directory to add"
    )
    private Path path;

    @Override
    public Integer call() {

        Path projectRoot = Path.of(".").toAbsolutePath().normalize();

        try {
            if (!Files.isDirectory(projectRoot.resolve(".vrn"))) {
                System.err.println("Not a VRN repository.");
                return 1;
            }

            AddService.Result result = new AddService(projectRoot).add(path);

            if (result.selectedFiles() == 0) {
                System.out.println("No eligible files to stage; the directory is empty or its untracked files match ignore rules.");
                if (result.warning() != null) System.err.println("Warning: " + result.warning());
                return 0;
            }

            System.out.println("Files staged successfully.");
            if (result.warning() != null) System.err.println("Warning: " + result.warning());
            return 0;

        } catch (Exception e) {
            System.err.println(
                    "Failed to add files: " + e.getMessage().replace(projectRoot.toString(), "<project>")
            );
            return 1;
        }
    }
}
