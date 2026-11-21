package com.vrn;

import com.vrn.cli.AddCommand;
import com.vrn.cli.CommitCommand;
import com.vrn.cli.InitCommand;
import com.vrn.cli.StatusCommand;
import com.vrn.cli.StageDeleteCommand;
import com.vrn.cli.UnstageCommand;
import com.vrn.cli.LogCommand;
import com.vrn.cli.InspectCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "vrn",
        description = "Versioning for Developers",
        version = "VRN 1.0",
        mixinStandardHelpOptions = true,
        subcommands = {
                InitCommand.class,
                AddCommand.class,
                CommitCommand.class,
                StatusCommand.class,
                StageDeleteCommand.class,
                UnstageCommand.class,
                LogCommand.class,
                InspectCommand.class
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
