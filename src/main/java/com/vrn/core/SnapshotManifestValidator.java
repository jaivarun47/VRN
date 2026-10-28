package com.vrn.core;

import java.util.Map;
import java.util.TreeMap;

/**
 * Lexical Windows-target manifest rules, deliberately independent of the test host.
 * Stored paths use '/', retain display spelling, and have no empty/dot components.
 * Java's locale-independent CASE_INSENSITIVE_ORDER conservatively identifies aliases,
 * for complete destinations, not shared-directory spelling; it is not a real-path probe.
 * Case-distinct colliding destinations require directory semantics not yet supported here.
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
        Node root = new Node("");
        for (String path : paths) {
            String[] parts = components(path);
            Node current = root;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean last = i == parts.length - 1;
                Node child = current.children.get(part);
                if (child == null) {
                    child = new Node(path);
                    current.children.put(part, child);
                } else {
                    if (!last && child.file != null) {
                        throw conflict(child.file, path);
                    }
                    if (last && !child.children.isEmpty()) {
                        throw conflict(path, child.example);
                    }
                }
                current = child;
            }
            if (current.file != null) {
                if (current.file.equals(path)) {
                    throw invalid("invalid snapshot structure: duplicate destination: '" + path + "'");
                }
                throw invalid("VRN support limitation: case-equivalent destination collision between '"
                        + current.file + "' and '" + path + "'; case-distinct directory semantics are not yet supported");
            }
            current.file = path;
        }
    }

    private static String[] components(String path) {
        if (path == null || path.isEmpty()) {
            throw invalid("invalid snapshot structure: file path must be nonempty");
        }
        if (path.startsWith("/") || (path.length() >= 2 && path.charAt(1) == ':')) {
            throw invalid("unsafe repository boundary: absolute or drive-qualified path: '" + path + "'");
        }
        if (path.indexOf('\\') >= 0) {
            throw invalid("invalid snapshot structure: canonical '/' separators are required: '" + path + "'");
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.equals("..")) {
                throw invalid("unsafe repository boundary: '..' traversal component in '" + path + "'");
            }
            if (part.isEmpty() || part.equals(".")) {
                throw invalid("invalid snapshot structure: empty or '.' component in '" + path + "'");
            }
            if (part.length() > 255) {
                throw invalid("VRN support limitation: component exceeds the supported 255 UTF-16 units: '" + path + "'");
            }
            if (part.endsWith(" ")) {
                throw invalid("Windows/Java path restriction: trailing ASCII space in '" + path + "'");
            }
            if (part.endsWith(".")) {
                throw invalid("ambiguous Windows destination: trailing period may resolve to a different name: '" + path + "'");
            }
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (ch < 0x20 || "<>:\"|?*".indexOf(ch) >= 0) {
                    throw invalid("Windows/Java path restriction: invalid path character in '" + path + "'");
                }
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= part.length() || !Character.isLowSurrogate(part.charAt(++i))) {
                        throw invalid("file path must contain valid Unicode: '" + path + "'");
                    }
                } else if (Character.isLowSurrogate(ch)) {
                    throw invalid("file path must contain valid Unicode: '" + path + "'");
                }
            }
            String stem = part.split("\\.", 2)[0];
            // Unicode whitespace is a real part of a filename, not a DOS namespace trim rule.
            while (stem.endsWith(" ")) {
                stem = stem.substring(0, stem.length() - 1);
            }
            if (stem.matches("(?i:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]|CONIN\\$|CONOUT\\$)")) {
                throw invalid("VRN support limitation: DOS device-like component in '" + path
                        + "'; namespace-safe support is not yet implemented");
            }
        }
        if (parts[0].equalsIgnoreCase(".vrn") || parts[0].equalsIgnoreCase(".vrnauto")) {
            throw invalid("unsafe repository boundary: reserved internal repository path: '" + path + "'");
        }
        return parts;
    }

    private static IllegalArgumentException conflict(String file, String descendant) {
        String category = descendant.startsWith(file + "/") ? "invalid snapshot structure: "
                : "VRN support limitation under case-equivalent identity: ";
        return invalid(category + "file/directory conflict between '" + file + "' and '" + descendant
                + "'; deletion and file/directory replacement are not supported");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid VRN manifest: " + message);
    }

    private static final class Node {
        private final String example;
        private final Map<String, Node> children = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private String file;

        private Node(String example) {
            this.example = example;
        }
    }
}
