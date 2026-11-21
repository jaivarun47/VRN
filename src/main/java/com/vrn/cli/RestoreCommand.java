package com.vrn.cli;

import com.vrn.core.RestorePlanner;
import com.vrn.core.RestoreService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import java.nio.file.*;
import java.util.concurrent.Callable;
import static com.vrn.cli.CliText.*;

@Command(name="restore", description="Recover historical working contents without moving HEAD; preview with --plan, recover with --recover")
public final class RestoreCommand implements Callable<Integer> {
    @Parameters(index="0", arity="0..1", paramLabel="<snapshot-id|HEAD>") private String selector;
    @Option(names="--plan", description="Read-only preview; do not restore files") private boolean preview;
    @Option(names="--recover", description="Explicitly recover a pending working-tree restoration") private boolean recover;
    @Spec private CommandSpec spec;

    @Override public Integer call() {
        if ((recover && (selector != null || preview)) || (!recover && selector == null)) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Use restore <id|HEAD>, restore --plan <id|HEAD>, or restore --recover");
        }
        Path root=Path.of(".").toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(root.resolve(".vrn"),LinkOption.NOFOLLOW_LINKS)) { write(System.err,"Not a VRN repository.\n"); return 1; }
            if (preview) {
                var plan=new RestorePlanner(root).plan(selector);
                var output=new StringBuilder("Restore plan for ").append(plan.selection().target().id())
                        .append("; HEAD remains ").append(plan.selection().headId()).append('\n');
                for (var action:plan.actions()) {
                    output.append(action.operation()).append(' ').append(action.safety()).append(' ').append(quote(action.path())).append('\n');
                    for (String reason:action.reasons()) output.append("  Reason: ").append(quote(reason)).append('\n');
                    if (!action.prerequisites().isEmpty()) output.append("  Prerequisites: ").append(quote(String.join(", ",action.prerequisites()))).append('\n');
                }
                boolean noOp=RestoreService.noOp(plan);
                output.append(noOp?"Already matches; no files need changing.\n":"Preview only; no files changed.\n");
                write(System.out,output.toString());
                return System.out.checkError() || (!plan.isSafe() && !noOp) ? 1 : 0;
            }
            var service=new RestoreService(root);
            if (recover) write(System.out,service.recover()+".\n");
            else {
                var result=service.restore(selector);
                write(System.out,(result.alreadyMatches()?"Already matches snapshot ":"Restored snapshot ")+result.target()+"; HEAD and staging unchanged.\n");
            }
            return System.out.checkError()?1:0;
        } catch (Exception failure) {
            String reason=String.valueOf(failure.getMessage()).replace(root.toString(),"<project>");
            write(System.err,"Restore failed: "+quote(reason)+"\n"); return 1;
        }
    }
}
