package com.vrn.cli;

import picocli.CommandLine.Command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;

@Command(
        name = "init",
        description = "Initialize a VRN repository"
)
public class InitCommand implements Runnable {

    @Override
    public void run() {
        Path currentDirectory = Path.of(".");
        Path vrnDirectory = currentDirectory.resolve(".vrn");
        Path stagingDirectory = vrnDirectory.resolve("staging");


        Path objectsDirectory = vrnDirectory.resolve("objects");
        Path snapshotsDirectory = vrnDirectory.resolve("snapshots");

        Path headFile = vrnDirectory.resolve("HEAD");
        Path configFile = vrnDirectory.resolve("config");

        Path ignoreFile = currentDirectory.resolve(".vrnignore");

        try {
            if (Files.exists(vrnDirectory)) {
                System.out.println("VRN repository already exists.");
                return;
            }

            Files.createDirectories(objectsDirectory);
            Files.createDirectories(snapshotsDirectory);
            Files.createDirectories(stagingDirectory);

            Files.createFile(headFile);
            Files.createFile(configFile);

            Files.writeString(headFile, "NONE");
            Files.writeString(configFile, "version=1");
            Files.writeString(ignoreFile, ".vrn/\n");

            DosFileAttributeView attributes =
                    Files.getFileAttributeView(
                            vrnDirectory,
                            DosFileAttributeView.class
                    );

            if (attributes != null) {
                attributes.setHidden(true);
            }

            System.out.println("Initialized empty VRN repository.");

        } catch (Exception e) {
            System.err.println(
                    "Failed to initialize VRN repository: " + e.getMessage()
            );
        }
    }
}