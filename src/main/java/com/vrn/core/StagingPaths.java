package com.vrn.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/** Single-file selection: exact stored spelling when absent; actual entry identity when present. */
final class StagingPaths {
    private final String requested;
    private final WorkingTreePaths identity;
    private final Path actual;

    StagingPaths(Path root, Path selection) throws IOException {
        requested = selection.toString().replace('\\', '/');
        SnapshotManifestValidator.validatePath(requested);
        identity = new WorkingTreePaths(root);
        actual = identity.selectedFile(requested);
    }

    String match(Collection<String> paths) throws IOException {
        String found = null;
        for (String path : paths) {
            if (!path.equalsIgnoreCase(requested)) continue;
            Path candidate = identity.selectedFile(path);
            boolean matches = actual != null && candidate != null
                    ? actual.toString().equals(candidate.toString()) : path.equals(requested);
            if (!matches) {
                if (actual == null && candidate == null) {
                    throw new IOException("VRN support limitation: missing case-equivalent selection is ambiguous; use exact stored spelling: " + path);
                }
                continue;
            }
            if (found != null) throw new IOException("VRN support limitation: ambiguous selected destination: " + requested);
            found = path;
        }
        return found;
    }
}
