package com.vrn.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static com.vrn.core.RestorePlan.*;

/** Conservative planning only. This class has no filesystem-write or recovery operations. */
public final class RestorePlanner {
    private final Path root;
    private final Path storage;
    private final SnapshotInspector inspector;
    private final FileHasher hasher;

    public RestorePlanner(Path root) {
        this(root, new SnapshotInspector(root.toAbsolutePath().normalize().resolve(".vrn")), new FileHasher());
    }

    RestorePlanner(Path root, SnapshotInspector inspector, FileHasher hasher) {
        this.root = root.toAbsolutePath().normalize();
        this.storage = this.root.resolve(".vrn");
        this.inspector = inspector;
        this.hasher = hasher;
    }

    public RestorePlan plan(String selector) throws Exception {
        var selection = inspector.verifiedSelection(selector);
        StagingIndex.apply(selection.head().files(), selection.staging()); // Validate, never publish.
        var observation = new Observation(root.toRealPath(), selection);
        RestorePlan result = observation.plan();
        new SnapshotResolver(storage).requireUnchanged(selection);
        return result;
    }

    private final class Observation {
        private final Path project;
        private final SnapshotResolver.Selection selected;
        private final Map<String, String> head;
        private final Map<String, String> target;
        private final Map<String, String> headOwners = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Map<String, String> targetOwners = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Set<String> headDirectories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        private final Map<String, ExpectedPath> observed = new TreeMap<>();
        private final Map<String, Action> removals = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Set<DirectoryChange> directoryChanges = new LinkedHashSet<>();
        private final Map<DirectoryScope, Check> directoryChecks = new HashMap<>();

        Observation(Path project, SnapshotResolver.Selection selected) {
            this.project = project;
            this.selected = selected;
            this.head = selected.head().files();
            this.target = selected.target().files();
            head.keySet().forEach(path -> {
                headOwners.put(path, path);
                for (int i = 1; i < path.split("/").length; i++) headDirectories.add(StagingIndex.prefix(path, i));
            });
            target.keySet().forEach(path -> targetOwners.put(path, path));
        }

        RestorePlan plan() throws Exception {
            BasicFileAttributes rootAttributes = Files.readAttributes(project, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            observed.put("", state("", "", rootAttributes, null, null, null));
            for (String path : new TreeSet<>(head.keySet())) {
                if (targetOwners.containsKey(path)) continue;
                ExpectedPath current = observe(path);
                Check check = new Check();
                check.problem(current);
                boolean change = current.type() == Type.FILE;
                if (change && (!Objects.equals(current.contentHash(), head.get(path)) || !path.equals(current.actualPath()))) {
                    check.conflict("Working file differs from HEAD content or spelling");
                }
                check.staging(path);
                check.reasons.add(change ? "HEAD-owned path is absent from target" : "HEAD-owned file already absent; preserve any replacement directory");
                removals.put(path, action(Operation.REMOVE, path, current, path, null, change, check));
            }
            var actions = new ArrayList<>(removals.values());
            Set<String> incompatibleParents = incompatibleTargetParents();
            for (String path : new TreeSet<>(target.keySet())) {
                ExpectedPath current = observe(path);
                String owned = headOwners.get(path);
                boolean change = current.type() != Type.FILE || !Objects.equals(current.contentHash(), target.get(path))
                        || !current.actualPath().equals(path);
                Operation operation = !change ? Operation.KEEP : current.type() == Type.MISSING ? Operation.CREATE : Operation.REPLACE;
                Check check = new Check();
                check.problem(current);
                check.staging(path);
                if (incompatibleParents.stream().anyMatch(parent -> StagingIndex.ancestor(parent, path))) {
                    check.unsupported("Target requires incompatible shared-directory spellings on Windows");
                }
                if (current.type() == Type.FILE) {
                    if (owned == null) check.conflict("Untracked destination must be preserved, including ignored/staged-only files");
                    else if (change && (!Objects.equals(current.contentHash(), head.get(owned)) || !current.actualPath().equals(owned))) {
                        check.conflict("Working file differs from HEAD content or spelling");
                    }
                }
                if (current.type() == Type.DIRECTORY) checkDirectory(current.actualPath(), false, check);
                String[] parts = path.split("/");
                for (int i = 1; i < parts.length; i++) {
                    String prefix = StagingIndex.prefix(path, i);
                    ExpectedPath parent = observe(prefix);
                    check.problem(parent);
                    if (parent.type() == Type.FILE) requireRemoval(parent.actualPath(), check);
                    if (parent.type() == Type.DIRECTORY && !prefix.equals(parent.actualPath())) {
                        directoryChanges.add(new DirectoryChange(parent.actualPath(), prefix));
                        checkDirectory(parent.actualPath(), true, check);
                    }
                }
                check.reasons.add(!change ? "Working file already matches target" : "Recover target bytes and exact stored spelling");
                actions.add(action(operation, path, current, owned, target.get(path), change, check));
            }
            actions.sort(Comparator.comparing(Action::path).thenComparing(Action::operation));
            return new RestorePlan(project, selected, actions, new ArrayList<>(observed.values()), new ArrayList<>(directoryChanges));
        }

        private Action action(Operation operation, String path, ExpectedPath current, String owned,
                              String targetHash, boolean change, Check check) {
            return new Action(operation, path, current.actualPath(), owned, owned == null ? null : head.get(owned),
                    targetHash, change, check.safety, check.reasons, new ArrayList<>(check.prerequisites));
        }

        private void requireRemoval(String actual, Check check) {
            String owned = headOwners.get(actual);
            Action removal = owned == null ? null : removals.get(owned);
            if (removal == null || !removal.requiresChange()) {
                check.conflict("Obstructing path is not an authorized clean tracked removal: " + actual);
                return;
            }
            check.prerequisites.add(removal.path());
            check.merge(removal.safety(), "Requires removal of '" + removal.path() + "': " + removal.safety());
        }

        /** Enumerate only affected subtrees, including ignored entries; never follow reparse entries. */
        private void checkDirectory(String directory, boolean spellingChange, Check check) throws Exception {
            DirectoryScope scope = new DirectoryScope(directory, spellingChange);
            Check assessment = directoryChecks.get(scope);
            if (assessment == null) {
                assessment = new Check();
                scanDirectory(directory, spellingChange, assessment);
                directoryChecks.put(scope, assessment);
            }
            if (assessment.safety.ordinal() > check.safety.ordinal()) check.safety = assessment.safety;
            check.reasons.addAll(assessment.reasons);
            check.prerequisites.addAll(assessment.prerequisites);
        }

        private void scanDirectory(String directory, boolean spellingChange, Check check) throws Exception {
            var pending = new ArrayDeque<String>();
            pending.add(directory);
            while (!pending.isEmpty()) {
                String path = pending.removeFirst();
                ExpectedPath current = observe(path);
                check.problem(current);
                if (current.type() == Type.UNSUPPORTED) continue;
                if (current.type() == Type.FILE) {
                    String owned = headOwners.get(current.actualPath());
                    if (spellingChange) {
                        if (owned == null || !Objects.equals(head.get(owned), current.contentHash()) || !owned.equals(current.actualPath())) {
                            check.conflict("Directory spelling change affects untracked or modified file: " + path);
                        }
                    } else requireRemoval(current.actualPath(), check);
                    continue;
                }
                if (current.type() != Type.DIRECTORY) {
                    check.unsupported("Directory changed during planning: " + path);
                    continue;
                }
                boolean ownedDirectory = headDirectories.contains(current.actualPath());
                if (!ownedDirectory) check.conflict("Untracked directory must be preserved: " + path);
                check.staging(current.actualPath());
                try {
                    var names = new ArrayList<String>();
                    // Fresh boundary observation immediately before opening, still not race-free.
                    new WorkingTreePaths(project).directoryForTransition(path);
                    try (var entries = Files.newDirectoryStream(project.resolve(current.actualPath()))) {
                        for (Path entry : entries) names.add(entry.getFileName().toString());
                    }
                    Collections.sort(names);
                    observed.put(path, new ExpectedPath(current.requestedPath(), current.actualPath(), current.type(), current.contentHash(),
                            current.fileKey(), current.size(), current.modified(), current.created(), names, current.blockingAncestor(), current.problem()));
                    for (String name : names) pending.add(current.actualPath() + "/" + name);
                } catch (IOException failure) {
                    check.unsupported("Cannot safely enumerate directory: " + path + ": " + failure.getMessage());
                }
            }
        }

        private ExpectedPath observe(String path) {
            ExpectedPath cached = observed.get(path);
            if (cached != null) return cached;
            try {
                for (var probe : new WorkingTreePaths(project).observeDestination(path)) {
                    if (observed.containsKey(probe.requested())) continue;
                    String hash = null;
                    String problem = probe.problem();
                    if (problem == null && probe.attributes() != null && probe.attributes().isRegularFile()) {
                        try {
                            BasicFileAttributes before = ContentCapture.observe(project, Path.of(probe.actual()));
                            hash = hasher.hash(project.resolve(probe.actual()));
                            BasicFileAttributes after = ContentCapture.observe(project, Path.of(probe.actual()));
                            if (!stable(probe.attributes(), before) || !stable(before, after)) throw new IOException("Working file changed during planning; retry");
                        } catch (Exception failure) { problem = "Cannot safely hash working file: " + probe.actual() + ": " + failure.getMessage(); }
                    }
                    observed.put(probe.requested(), state(probe.requested(), probe.actual(), probe.attributes(), hash, probe.blockingAncestor(), problem));
                }
            } catch (Exception failure) {
                observed.put(path, state(path, path, null, null, null, failure.toString()));
            }
            return observed.get(path);
        }

        private Set<String> incompatibleTargetParents() {
            var spellings = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            var incompatible = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            for (String path : target.keySet()) {
                for (int i = 1; i < path.split("/").length; i++) {
                    String prefix = StagingIndex.prefix(path, i);
                    String previous = spellings.putIfAbsent(prefix, prefix);
                    if (previous != null && !previous.equals(prefix)) incompatible.add(prefix);
                }
            }
            return incompatible;
        }

        private final class Check {
            Safety safety = Safety.SAFE;
            final List<String> reasons = new ArrayList<>();
            final Set<String> prerequisites = new TreeSet<>();
            void merge(Safety value, String reason) { if (value.ordinal() > safety.ordinal()) safety = value; reasons.add(reason); }
            void conflict(String reason) { merge(Safety.CONFLICT, reason); }
            void unsupported(String reason) { merge(Safety.UNSUPPORTED, reason); }
            void problem(ExpectedPath path) { if (path.problem() != null || path.type() == Type.UNSUPPORTED) unsupported("Unsafe observation: " + path.requestedPath() + ": " + path.problem()); }
            void staging(String path) {
                selected.staging().keySet().stream().filter(staged -> StagingIndex.related(path, staged))
                        .forEach(staged -> conflict("Preserve related staging intent: " + staged));
            }
        }
    }

    private record DirectoryScope(String path, boolean spellingChange) {}

    private static boolean stable(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
                && before.creationTime().equals(after.creationTime()) && Objects.equals(before.fileKey(), after.fileKey());
    }

    private static ExpectedPath state(String requested, String actual, BasicFileAttributes attrs, String hash, String blocker, String problem) {
        Type type = problem != null ? Type.UNSUPPORTED : attrs == null ? Type.MISSING : attrs.isRegularFile() ? Type.FILE : Type.DIRECTORY;
        return new ExpectedPath(requested, actual, type, hash, attrs == null || attrs.fileKey() == null ? null : attrs.fileKey().toString(),
                attrs == null ? 0 : attrs.size(), attrs == null ? null : attrs.lastModifiedTime(), attrs == null ? null : attrs.creationTime(), null, blocker, problem);
    }
}
