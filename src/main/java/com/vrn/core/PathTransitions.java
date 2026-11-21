package com.vrn.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Reconcile only obstructions and proven case aliases necessary for the selected add. */
final class PathTransitions {
    private PathTransitions() {}

    static void prepare(Path root, Path selection, Map<String, String> head,
                        Map<String, StagedOperation> entries, List<SelectedFile> selected) throws Exception {
        var identity = new WorkingTreePaths(root);
        var known = new LinkedHashSet<>(head.keySet());
        entries.forEach((path, operation) -> { if (operation instanceof StagedOperation.Put) known.add(path); });
        var actuals = new HashSet<String>();
        for (var file : selected) actuals.add(canonical(file.source()));
        // A caller-selected project alias remains an anchor; internal aliases do not.
        Path target = root.toRealPath().resolve(root.relativize(root.resolve(selection).normalize()));
        var attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Unsupported selected reparse entry");
        boolean directory = attrs.isDirectory();
        String scope = canonical(directory ? identity.directory(target) : identity.file(target));
        var checked = new HashSet<String>();
        for (var file : selected) {
            String actual = canonical(file.source());
            for (String old : known) {
                String[] change = StagingIndex.directoryChange(old, actual);
                if (change == null) continue;
                String actualDirectory = canonical(identity.directoryForTransition(change[0]));
                if (!actualDirectory.equals(change[1])) throw ambiguous(old, actual);
                if (!directory || !(scope.isEmpty() || actualDirectory.equals(scope) || actualDirectory.startsWith(scope + "/"))) {
                    throw new IOException("Directory capitalization changed: '" + change[0] + "' -> '" + change[1]
                            + "'; add '" + change[1] + "' or an ancestor to select the complete transition");
                }
                if (!checked.add(actualDirectory)) continue;
                for (String affected : known) {
                    if (!StagingIndex.ancestor(change[0], affected)) continue;
                    Path current = identity.trackedFile(affected);
                    if (current == null) {
                        if (entries.get(affected) instanceof StagedOperation.Delete) continue;
                        if (actuals.stream().anyMatch(path -> StagingIndex.related(affected, path))) continue;
                        throw new IOException("Cannot complete directory capitalization transition: missing selected file '"
                                + affected + "'; restore it or explicitly stage-delete/unstage its selection first");
                    }
                    if (!actuals.contains(canonical(current))) throw new IOException("Incomplete directory selection: " + affected);
                }
            }
        }
        for (var file : selected) {
            String actual = canonical(file.source());
            for (String old : known) {
                if (old.equals(actual) || !StagingIndex.related(old, actual)) continue;
                if (old.equalsIgnoreCase(actual)) {
                    Path current = identity.trackedFile(old);
                    if (current == null || !canonical(current).equals(actual)) throw ambiguous(old, actual);
                } else if (StagingIndex.ancestor(old, actual)) {
                    String current = canonical(identity.directoryForTransition(old));
                    if (!actual.startsWith(current + "/")) throw ambiguous(old, actual);
                } else {
                    String oldAncestor = StagingIndex.prefix(old, actual.split("/").length);
                    Path current = identity.selectedFile(oldAncestor);
                    if (current == null || !canonical(current).equals(actual)) throw ambiguous(old, actual);
                }
                if (entries.get(old) instanceof StagedOperation.Put) entries.remove(old);
                if (head.containsKey(old)) entries.put(old, new StagedOperation.Delete());
            }
        }
    }

    private static IOException ambiguous(String old, String actual) {
        return new IOException("Cannot prove an unambiguous structural transition: '" + old + "' -> '" + actual + "'");
    }
    private static String canonical(Path path) { return path.toString().replace('\\', '/'); }
}
