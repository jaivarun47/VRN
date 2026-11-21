package com.vrn.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Typed index structure differs from a snapshot; validate the applied manifest separately. */
final class StagingIndex {
    private StagingIndex() {}

    static void validate(Map<String, StagedOperation> operations) {
        var puts = new ArrayList<String>();
        var deletes = new ArrayList<String>();
        for (var entry : operations.entrySet()) {
            SnapshotManifestValidator.validatePath(entry.getKey());
            switch (entry.getValue()) {
                case StagedOperation.Put ignored -> puts.add(entry.getKey());
                case StagedOperation.Delete ignored -> deletes.add(entry.getKey());
            }
        }
        SnapshotManifestValidator.validatePaths(puts);
        SnapshotManifestValidator.validatePaths(deletes);
    }

    static Map<String, String> apply(Map<String, String> head, Map<String, StagedOperation> operations) {
        validate(operations);
        var result = new LinkedHashMap<>(head);
        for (var entry : operations.entrySet()) {
            if (entry.getValue() instanceof StagedOperation.Delete && result.remove(entry.getKey()) == null) {
                throw new IllegalArgumentException("DELETE path is not tracked in HEAD: " + entry.getKey());
            }
        }
        for (var entry : operations.entrySet()) {
            if (entry.getValue() instanceof StagedOperation.Put put) result.put(entry.getKey(), put.contentHash());
        }
        SnapshotManifestValidator.validatePaths(result.keySet());
        // Historical manifests may contain parent spelling aliases. A newly selected
        // directory spelling must not leave inherited descendants at the old spelling.
        for (var entry : operations.entrySet()) {
            if (!(entry.getValue() instanceof StagedOperation.Put) || head.containsKey(entry.getKey())) continue;
            for (String old : head.keySet()) {
                String[] change = directoryChange(old, entry.getKey());
                boolean selectedTransition = change != null && operations.entrySet().stream()
                        .anyMatch(operation -> operation.getValue() instanceof StagedOperation.Delete
                                && ancestor(change[0], operation.getKey()));
                if (selectedTransition && result.containsKey(old)) {
                    throw new IllegalArgumentException("Incomplete directory capitalization transition: '" + change[0]
                            + "' -> '" + change[1] + "'; select the whole changed directory or cancel replacement PUTs first");
                }
            }
        }
        return result;
    }

    static boolean ancestor(String parent, String child) {
        return child.length() > parent.length() && child.charAt(parent.length()) == '/'
                && child.regionMatches(true, 0, parent, 0, parent.length());
    }

    static boolean related(String a, String b) { return a.equalsIgnoreCase(b) || ancestor(a, b) || ancestor(b, a); }

    static String prefix(String path, int components) { return String.join("/", Arrays.copyOf(path.split("/"), components)); }

    static String[] directoryChange(String old, String actual) {
        String[] a = old.split("/");
        String[] b = actual.split("/");
        for (int i = 0; i < Math.min(a.length, b.length) - 1; i++) {
            if (!a[i].equalsIgnoreCase(b[i])) return null;
            if (!a[i].equals(b[i])) return new String[]{prefix(old, i + 1), prefix(actual, i + 1)};
        }
        return null;
    }
}
