package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public class FileHasher {

    /**
     * Hashes a file and returns only the hex hash string.
     * Kept for backward compatibility.
     */
    public String hash(Path file) throws Exception {
        return hashWithBytes(file).hash();
    }

    /**
     * Reads the file once, computes its SHA-256 hash, and returns
     * both the hash and the raw bytes together.
     *
     * <p>Use this instead of calling {@link #hash} followed by a
     * separate {@code Files.readAllBytes} call; doing so would read
     * the file twice and create a window where the file could change
     * between the two reads (TOCTOU bug).
     */
    public HashedFile hashWithBytes(Path file) throws Exception {

        byte[] content = Files.readAllBytes(file);

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