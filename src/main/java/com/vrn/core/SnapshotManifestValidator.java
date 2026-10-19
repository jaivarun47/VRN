package com.vrn.core;

import java.util.Map;
import java.util.TreeMap;

/**
 * Lexical Windows-target manifest rules, deliberately independent of the test host.
 * Stored paths use '/', retain display spelling, and have no empty/dot components.
 * Java's locale-independent CASE_INSENSITIVE_ORDER conservatively identifies aliases,
 * including directory spelling aliases; it is not an NTFS upcase-table or real-path probe.
 * No Unicode normalization, disk access, object checks or ancestry checks are performed.
 * See docs/snapshot-path-model.md for the support boundary.
 */
public final class SnapshotManifestValidator {

    private SnapshotManifestValidator() {
    }

    public static void validatePath(String path) {
        components(path);
    }

    public static void validatePaths(Iterable<String> paths) {
        if (paths == null) {
            throw invalid("file paths are required");
        }
        Node root = new Node("", "");
        for (String path : paths) {
            String[] parts = components(path);
            Node current = root;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean last = i == parts.length - 1;
                Node child = current.children.get(part);
                if (child == null) {
                    child = new Node(part, path);
                    current.children.put(part, child);
                } else {
                    if (!last && child.file != null) {
                        throw conflict(child.file, path);
                    }
                    if (last && !child.children.isEmpty()) {
                        throw conflict(path, child.example);
                    }
                    if (!child.spelling.equals(part)) {
                        throw invalid("case-insensitive destination or directory-spelling collision between '"
                                + child.example + "' and '" + path + "'");
                    }
                }
                current = child;
            }
            if (current.file != null) {
                throw invalid("duplicate destination: '" + current.file + "' and '" + path + "'");
            }
            current.file = path;
        }
    }

    private static String[] components(String path) {
        if (path == null || path.isEmpty()) {
            throw invalid("file path must be nonempty");
        }
        if (path.indexOf('\\') >= 0 || path.startsWith("/")) {
            throw invalid("path must be repository-relative with canonical '/' separators: '" + path + "'");
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw invalid("empty, '.' and '..' path components are not allowed: '" + path + "'");
            }
            if (part.length() > 255 || part.startsWith(" ") || part.endsWith(" ") || part.endsWith(".")) {
                throw invalid("unsupported Windows component (length, leading/trailing space or trailing dot): '"
                        + path + "'");
            }
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (Character.isISOControl(ch) || "<>:\"|?*".indexOf(ch) >= 0) {
                    throw invalid("invalid Windows path character in '" + path + "'");
                }
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= part.length() || !Character.isLowSurrogate(part.charAt(++i))) {
                        throw invalid("file path must contain valid Unicode: '" + path + "'");
                    }
                } else if (Character.isLowSurrogate(ch)) {
                    throw invalid("file path must contain valid Unicode: '" + path + "'");
                }
            }
            String stem = part.split("\\.", 2)[0].stripTrailing();
            if (stem.matches("(?i:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]|CONIN\\$|CONOUT\\$)")) {
                throw invalid("reserved Windows device name in '" + path + "'");
            }
        }
        if (parts[0].equalsIgnoreCase(".vrn") || parts[0].equalsIgnoreCase(".vrnauto")) {
            throw invalid("reserved internal repository path: '" + path + "'");
        }
        return parts;
    }

    private static IllegalArgumentException conflict(String file, String descendant) {
        return invalid("file/directory conflict between '" + file + "' and '" + descendant
                + "'; deletion and file/directory replacement are not supported");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid VRN manifest: " + message);
    }

    private static final class Node {
        private final String spelling;
        private final String example;
        private final Map<String, Node> children = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private String file;

        private Node(String spelling, String example) {
            this.spelling = spelling;
            this.example = example;
        }
    }
}
