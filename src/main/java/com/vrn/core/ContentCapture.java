package com.vrn.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Sequential, bounded capture; attribute checks detect evidence of instability, not isolation. */
class ContentCapture {
    static final int BUFFER_SIZE = 64 * 1024;
    private final byte[] buffer = new byte[BUFFER_SIZE];

    String capture(Path root, SelectedFile selected, Path temporary) throws Exception {
        Path source = root.resolve(selected.source());
        BasicFileAttributes before = observe(root, selected.source());
        requireStable(selected.observed(), before);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (InputStream input = openSource(source);
             OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            requireStable(before, observe(root, selected.source()));
            for (int n; (n = read(input, buffer)) != -1;) {
                if (n == 0) continue;
                count += n;
                if (count > before.size()) throw unstable();
                digest.update(buffer, 0, n);
                write(output, buffer, n);
            }
        }
        if (count != before.size()) throw unstable();
        requireStable(before, observe(root, selected.source()));
        return HexFormat.of().formatHex(digest.digest());
    }

    static BasicFileAttributes observe(Path root, Path relative) throws IOException {
        String canonical = relative.toString().replace('\\', '/');
        SnapshotManifestValidator.validatePath(canonical);
        Path actual = new WorkingTreePaths(root).trackedFile(canonical);
        if (actual == null || !actual.toString().equals(relative.toString())) throw unstable();
        BasicFileAttributes attributes = Files.readAttributes(root.resolve(relative), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) throw unstable();
        return attributes;
    }

    private static void requireStable(BasicFileAttributes before, BasicFileAttributes after) throws IOException {
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()) || !before.creationTime().equals(after.creationTime())) {
            throw unstable();
        }
    }

    private static IOException unstable() { return new IOException("Selected source changed or became unavailable during capture; retry the add operation"); }

    // Narrow seams permit deterministic source changes, read failures and temporary-write failures.
    protected InputStream openSource(Path source) throws IOException {
        return Channels.newInputStream(Files.newByteChannel(source, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)));
    }

    protected int read(InputStream input, byte[] bytes) throws IOException { return input.read(bytes); }
    protected void write(OutputStream output, byte[] bytes, int length) throws IOException { output.write(bytes, 0, length); }
}
