package com.vrn.cli;

import com.vrn.core.RepositoryInitializer;
import picocli.CommandLine.Command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "init",
        description = "Initialize a VRN repository"
)
public class InitCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        try {
            boolean created = new RepositoryInitializer().initialize(Path.of("."));
            if (created) {
                System.out.println("Initialized empty VRN repository.");
            } else {
                System.out.println("VRN repository is already initialized and valid; initialization is unnecessary.");
            }
            return 0;
        } catch (Exception e) {
            System.err.println(
                    "Failed to initialize VRN repository: " + e.getMessage()
            );
            return 1;
        }
    }
}
