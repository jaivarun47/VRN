package com.vrn.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Shared UTF-8 output and reversible quoting for observational commands. */
final class CliText {
    private CliText() {}

    /** Reversible quoted representation; control/format characters cannot spoof another entry. */
    static String quote(String value) {
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

    static void write(PrintStream stream,String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes,0,bytes.length);
        stream.flush();
    }
}
