package com.vrn.cli;

import picocli.CommandLine.Command;

import java.nio.file.Files;
import java.nio.file.Path;

@Command(
        name = "init",
        description = "Initialize a VRN repository"
)
public class InitCommand implements Runnable {

    @Override
    public void run() {
        Path currentDirectory = Path.of(".");
        Path vrnDirectory = currentDirectory.resolve(".vrn");

        try {
            if (Files.exists(vrnDirectory)) {
                System.out.println("VRN repository already exists.");
                return;
            }

            Files.createDirectory(vrnDirectory);
            System.out.println("Initialized empty VRN repository.");
        } catch (Exception e) {
            System.err.println("Failed to initialize VRN repository: " + e.getMessage());
        }
    }
}