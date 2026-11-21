package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.Channels;
import java.util.Set;
import java.security.MessageDigest;

public class FileHasher {

    /**
     * Hashes a file and returns only the hex hash string.
     * Kept for backward compatibility.
     */
    public String hash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[ContentCapture.BUFFER_SIZE];
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) {
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return bytesToHex(digest.digest());
    }

    /**
     * Compatibility helper for callers explicitly requesting bytes; not used by add or status.
     * Reads the file once, computes its SHA-256 hash, and returns
     * both the hash and the raw bytes together.
     *
     * <p>Use this instead of calling {@link #hash} followed by a
     * separate {@code Files.readAllBytes} call; doing so would read
     * the file twice and create a window where the file could change
     * between the two reads (TOCTOU bug).
     */
    public HashedFile hashWithBytes(Path file) throws Exception {

        byte[] content;
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) {
            content = input.readAllBytes();
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(content);

        return new HashedFile(bytesToHex(hashBytes), content);
    }

    private String bytesToHex(byte[] bytes) {

        StringBuilder hex = new StringBuilder();

        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }

        return hex.toString();
    }

    /**
     * Pairs the SHA-256 hex hash with the raw file bytes that produced it.
     */
    public record HashedFile(String hash, byte[] bytes) {
    }
}
