package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class VrnIgnore {

    private final List<String> rules;

    public VrnIgnore(Path ignoreFile) throws Exception {
        this.rules = Files.readAllLines(ignoreFile)
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