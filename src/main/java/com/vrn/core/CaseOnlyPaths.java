package com.vrn.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/** Single-PUT safety guard; complete transitions are selected through AddService. */
final class CaseOnlyPaths {
    private CaseOnlyPaths() {}

    static boolean fileNameChanged(String recorded, String actual) {
        String first = recorded.substring(recorded.lastIndexOf('/') + 1);
        String second = actual.substring(actual.lastIndexOf('/') + 1);
        return !first.equals(second) && first.equalsIgnoreCase(second);
    }

    static String alternateSpelling(String selected, Collection<String> recordedPaths) {
        for (String recorded : recordedPaths) {
            if (!recorded.equals(selected) && recorded.equalsIgnoreCase(selected)) return recorded;
        }
        return null;
    }

    static void requirePut(Path projectRoot, String selected, Collection<String> committed,
                           Collection<String> staged) throws IOException {
        String alternate = alternateSpelling(selected, committed);
        if (alternate == null) alternate = alternateSpelling(selected, staged);
        if (alternate != null) throw unsupported(alternate, selected);
        Path actual = new WorkingTreePaths(projectRoot).trackedFile(selected);
        if (actual != null && fileNameChanged(selected, canonical(actual))) {
            throw unsupported(selected, canonical(actual));
        }
    }

    static IOException unsupported(String recorded, String selected) {
        return new IOException("Case-only path transition requires a complete add selection: '" + recorded + "' -> '" + selected
                + "'. Use add on the changed file, directory or an ancestor before committing.");
    }

    private static String canonical(Path path) { return path.toString().replace('\\', '/'); }
}
