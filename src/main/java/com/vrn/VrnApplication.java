package com.vrn;

import com.vrn.cli.AddCommand;
import com.vrn.cli.InitCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "vrn",
        description = "Versioning for Developers",
        version = "VRN 1.0",
        mixinStandardHelpOptions = true,
        subcommands = {
                InitCommand.class,
                AddCommand.class
        }
)
public class VrnApplication implements Runnable {

    @Override
    public void run() {
        System.out.println("VRN - Versioning for Developers");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new VrnApplication()).execute(args);
        System.exit(exitCode);
    }
}