package com.vrn.cli;

import com.vrn.core.FileScanner;
import com.vrn.core.ObjectStore;
import com.vrn.core.ScannedFile;
import com.vrn.core.StagingArea;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Command(
        name = "add",
        description = "Stage files for the next snapshot"
)
public class AddCommand implements Runnable {

    @Parameters(
            index = "0",
            description = "File or directory to add"
    )
    private Path path;

    @Override
    public void run() {

        Path projectRoot = Path.of(".").toAbsolutePath().normalize();

        try {

            if (!Files.exists(projectRoot.resolve(".vrn"))) {
                System.out.println("Not a VRN repository.");
                return;
            }

            FileScanner scanner = new FileScanner();
            ObjectStore objectStore =
                    new ObjectStore(projectRoot.resolve(".vrn"));
            StagingArea stagingArea =
                    new StagingArea(projectRoot.resolve(".vrn"));

            List<ScannedFile> files = scanner.scan(projectRoot);

            for (ScannedFile file : files) {

                Path actualFile =
                        projectRoot.resolve(file.path());

                objectStore.store(
                        actualFile,
                        file.hash()
                );

                stagingArea.stage(
                        file.path(),
                        file.hash()
                );
            }

            System.out.println("Files staged successfully.");

        } catch (Exception e){

            System.err.println(
                    "Failed to add files: " + e.getMessage()
            );
        }
    }
}