package com.vrn.cli;

import com.vrn.core.SnapshotInspector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import static com.vrn.cli.CliText.*;

@Command(name = "inspect", description = "Inspect and verify one reachable snapshot by full ID or HEAD")
public final class InspectCommand implements Callable<Integer> {
    @Parameters(index = "0", arity = "1", paramLabel = "<snapshot-id|HEAD>")
    private String selector;

    @Override public Integer call() {
        Path storage = Path.of(".").toAbsolutePath().normalize().resolve(".vrn");
        try {
            if (!Files.isDirectory(storage, LinkOption.NOFOLLOW_LINKS)) {
                write(System.err, "Not a VRN repository.\n");
                return 1;
            }
            var snapshot = new SnapshotInspector(storage).inspect(selector);
            var output = new StringBuilder("snapshot ").append(snapshot.id()).append('\n')
                    .append("Parent: ").append(snapshot.parentId()).append('\n')
                    .append("Date: ").append(Instant.ofEpochMilli(snapshot.timestamp())).append('\n')
                    .append("Message: ").append(quote(snapshot.message())).append('\n')
                    .append("Objects: verified\nFiles:\n");
            if (snapshot.files().isEmpty()) output.append("  (none)\n");
            snapshot.files().forEach((path, hash) -> output.append("  ").append(hash).append("  ").append(quote(path)).append('\n'));
            write(System.out, output.toString());
            return System.out.checkError() ? 1 : 0;
        } catch (Exception failure) {
            String reason = String.valueOf(failure.getMessage()).replace(storage.toString(), "<repository>");
            write(System.err, "Failed to inspect snapshot: " + quote(reason) + "\n");
            return 1;
        }
    }
}
