package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

public class VrnIgnore {

    private final List<String> rules;

    public VrnIgnore(Path ignoreFile) throws Exception {
        List<String> lines;
        try { lines = Files.readAllLines(ignoreFile); }
        catch (NoSuchFileException absent) {
            try {
                Files.readAttributes(ignoreFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missingEntry) {
                this.rules = List.of();
                return;
            }
            throw absent; // An existing dangling link is not an absent optional configuration.
        }
        this.rules = lines
                .stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    public boolean isIgnored(Path path) {

        String pathString = path.toString().replace('\\', '/');

        for (String rule : rules) {

            boolean directoryRule = rule.endsWith("/");

            String cleanRule = rule;

            if (directoryRule) {
                cleanRule = rule.substring(0, rule.length() - 1);
            }

            if (directoryRule) {
                if (pathString.equals(cleanRule)
                        || pathString.startsWith(cleanRule + "/")) {
                    return true;
                }
            } else {
                if (matches(cleanRule, pathString)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean matches(String rule, String path) {

        if (rule.equals("*")) {
            return true;
        }

        if (rule.contains("*")) {

            String[] parts = rule.split("\\*", -1);

            if (parts.length == 2) {
                return path.startsWith(parts[0])
                        && path.endsWith(parts[1]);
            }
        }

        return path.equals(rule)
                || path.endsWith("/" + rule);
    }
}
