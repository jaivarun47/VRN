package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public class FileHasher {

    public String hash(Path file) throws Exception {

        byte[] content = Files.readAllBytes(file);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        byte[] hashBytes = digest.digest(content);

        return bytesToHex(hashBytes);
    }

    private String bytesToHex(byte[] bytes) {

        StringBuilder hex = new StringBuilder();

        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }

        return hex.toString();
    }
}