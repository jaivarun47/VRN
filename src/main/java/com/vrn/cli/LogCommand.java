package com.vrn.cli;

import com.vrn.core.SnapshotHistory;
import picocli.CommandLine.Command;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name="log",description="Show committed snapshot ancestry, newest first")
public class LogCommand implements Callable<Integer> {
    @Override public Integer call() {
        Path storage=Path.of(".").toAbsolutePath().normalize().resolve(".vrn");
        try {
            if (!Files.isDirectory(storage,LinkOption.NOFOLLOW_LINKS)) {
                write(System.err,"Not a VRN repository.\n");
                return 1;
            }
            List<SnapshotHistory.Entry> entries=new SnapshotHistory(storage).read();
            StringBuilder output=new StringBuilder();
            if (entries.isEmpty()) output.append("No snapshots found.\n");
            for (var entry:entries) {
                output.append("commit ").append(entry.id()).append('\n')
                        .append("Parent: ").append(entry.parentId()).append('\n')
                        .append("Date: ").append(Instant.ofEpochMilli(entry.timestamp())).append('\n')
                        .append("Message: ").append(quote(entry.message())).append("\n\n");
            }
            // No normal output until every reachable manifest and display record is valid.
            write(System.out,output.toString());
            return System.out.checkError() ? 1 : 0;
        } catch (Exception failure) {
            String reason=String.valueOf(failure.getMessage()).replace(storage.toString(),"<repository>");
            write(System.err,"Failed to read history: "+quote(reason)+"\n");
            return 1;
        }
    }

    /** Reversible quoted representation; control/format characters cannot spoof another entry. */
    private static String quote(String value) {
        StringBuilder result=new StringBuilder("\"");
        value.codePoints().forEach(cp -> {
            switch (cp) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                default -> {
                    if (Character.isISOControl(cp) || Character.getType(cp)==Character.FORMAT || cp==0x2028 || cp==0x2029) {
                        for (char ch:Character.toChars(cp)) {
                            result.append("\\u");
                            for (int shift=12;shift>=0;shift-=4) result.append("0123456789abcdef".charAt((ch>>>shift)&15));
                        }
                    } else result.appendCodePoint(cp);
                }
            }
        });
        return result.append('"').toString();
    }

    private static void write(PrintStream stream,String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes,0,bytes.length);
        stream.flush();
    }
}
