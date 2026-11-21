package com.vrn.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static com.vrn.core.RestoreJournal.*;
import static com.vrn.core.RestoreFileOps.Event.*;
import static com.vrn.core.RestoreFailure.Status.*;

/** Recoverable working-tree changes only: never publishes HEAD, staging, snapshots or objects. */
public final class RestoreService {
    private final Path root;
    private final Path storage;
    private final RestoreFileOps files;

    public RestoreService(Path root) throws IOException { this(root.toRealPath(), new RestoreFileOps(root.toRealPath())); }
    RestoreService(Path root, RestoreFileOps files) throws IOException {
        this.root = root.toRealPath(); this.storage = this.root.resolve(".vrn"); this.files = files;
    }
    public record Result(String target, boolean alreadyMatches, String message) {}

    /** No destructive override: exact already-matching state needs no execution or ownership adoption. */
    public static boolean noOp(RestorePlan plan) {
        return plan.actions().stream().noneMatch(RestorePlan.Action::requiresChange)
                && plan.actions().stream().noneMatch(action -> action.safety() == RestorePlan.Safety.UNSUPPORTED)
                && plan.selection().staging().keySet().stream().noneMatch(staged -> plan.actions().stream().anyMatch(action -> StagingIndex.related(staged, action.path())));
    }

    private static void executable(RestorePlan plan) throws RestoreFailure {
        if (!plan.isSafe() && !noOp(plan)) {
            String conflicts = plan.actions().stream().filter(action -> action.safety() != RestorePlan.Safety.SAFE)
                    .map(action -> action.path() + " [" + action.safety() + "]: " + String.join("; ", action.reasons())).reduce((a,b) -> a + "\n" + b).orElse("Unsafe restore plan");
            throw new RestoreFailure(REFUSED, "Restoration refused; working files were not changed:\n" + conflicts, null);
        }
    }

    public Result restore(String selector) throws Exception {
        executable(new RestorePlanner(root).plan(selector)); // Reject obvious conflicts without even creating a lock.
        try (var guard = RepositoryMutationGuard.acquire(storage)) {
            RestorePlan plan = new RestorePlanner(root).plan(selector);
            executable(plan);
            if (noOp(plan)) return new Result(plan.selection().target().id(), true, "Working tree already matches; no files changed");
            files.qualified();
            RestoreJournal journal = catalog(plan);
            try {
                createArea();
                write(journal);
                journal = prepare(journal);
                revalidate(plan);
                baseline(journal);
                verifyAssets(journal);
                write(journal);
                List<Step> steps = journal.steps();
                for (int i = 0; i < steps.size(); i++) {
                    journal = journal.progress(Phase.APPLYING, i, true); write(journal);
                    anchor(journal); files.root(journal.rootStamp());
                    files.checkpoint(BEFORE_STEP, detail(steps.get(i)));
                    forward(journal, steps.get(i));
                    files.checkpoint(AFTER_STEP, detail(steps.get(i)));
                    require(after(journal, steps.get(i)), "Uncertain filesystem result: " + detail(steps.get(i)));
                    journal = journal.progress(Phase.APPLYING, i + 1, false); write(journal);
                }
                files.checkpoint(VERIFY_RESULT, journal.target());
                verifyTarget(plan.selection().target(), plan.selection().head());
                for (Item item : journal.targets()) require(files.file(item.path(), item, files.asset("prepared", item.id())), "Published file identity changed: " + item.path());
                baseline(journal);
                files.checkpoint(BEFORE_COMPLETE, journal.target());
                journal = journal.progress(Phase.COMPLETE, steps.size(), false); write(journal);
                cleanup(journal);
                return new Result(journal.target(), false, "Historical working contents restored; HEAD and staging unchanged");
            } catch (Exception failure) {
                RestoreJournal disk;
                try { disk = read(); }
                catch (Exception unreadable) {
                    failure.addSuppressed(unreadable);
                    throw new RestoreFailure(RECOVERY_REQUIRED, "Restore outcome requires recovery; journal/assets were preserved. Run 'vrn restore --recover': " + failure, failure);
                }
                if (disk != null && disk.phase() == Phase.COMPLETE) {
                    throw new RestoreFailure(COMPLETED_CLEANUP_PENDING, "Working tree restoration is confirmed complete, but cleanup is pending. Run 'vrn restore --recover': " + failure, failure);
                }
                String recovery;
                try { recovery = recoverLocked(); }
                catch (Exception blocked) {
                    failure.addSuppressed(blocked);
                    throw new RestoreFailure(RECOVERY_REQUIRED, "Restoration stopped; rollback could not be proven safe. Journal, backups and displaced bytes were preserved. Run 'vrn restore --recover': " + blocked.getMessage(), failure);
                }
                throw new RestoreFailure(disk == null || disk.phase() == Phase.PREPARING ? PREPARATION_FAILED : ROLLED_BACK,
                        "Restoration failed: " + failure.getMessage() + ". " + recovery, failure);
            }
        }
    }

    public String recover() throws Exception {
        try (var guard = RepositoryMutationGuard.acquireForRestoreRecovery(storage)) {
            files.qualified();
            return recoverLocked();
        } catch (RestoreFailure failure) { throw failure; }
        catch (Exception failure) { throw new RestoreFailure(RECOVERY_REQUIRED, "Restore recovery refused; existing state was preserved: " + failure.getMessage(), failure); }
    }

    private String recoverLocked() throws Exception {
        commitClear();
        RestoreJournal journal = read();
        if (journal == null) return bootstrapCleanup();
        baseline(journal);
        if (journal.finished()) { cleanup(journal); return journal.phase() == Phase.COMPLETE ? "Completed restoration cleanup finished" : "Rollback/preparation cleanup finished"; }
        if (journal.phase() == Phase.PREPARING) {
            inventory(journal);
            journal = journal.progress(Phase.ABORTED, 0, false); write(journal); cleanup(journal);
            return "Preparation aborted; no working-tree restoration was started";
        }
        verifyAssets(journal); // Mandatory verified backups before any rollback write.
        List<Step> steps = journal.steps();
        int cursor = journal.cursor();
        if (journal.phase() == Phase.APPLYING && journal.inFlight()) {
            Step uncertain = steps.get(cursor);
            if (after(journal, uncertain)) cursor++;
            else require(before(journal, uncertain), "Cannot reconcile interrupted step: " + detail(uncertain));
        }
        journal = journal.progress(Phase.ROLLING_BACK, cursor, false); write(journal);
        while (cursor > 0) {
            Step step = steps.get(cursor - 1);
            journal = journal.progress(Phase.ROLLING_BACK, cursor, true); write(journal);
            anchor(journal); files.root(journal.rootStamp());
            files.checkpoint(ROLLBACK_STEP, detail(step));
            if (!undone(journal, step)) {
                require(after(journal, step), "Recovery conflict; unexpected path/identity: " + detail(step));
                undo(journal, step);
            }
            files.checkpoint(AFTER_ROLLBACK_STEP, detail(step));
            require(undone(journal, step), "Uncertain rollback outcome: " + detail(step));
            cursor--; journal = journal.progress(Phase.ROLLING_BACK, cursor, false); write(journal);
        }
        verifyOriginals(journal);
        baseline(journal);
        journal = journal.progress(Phase.ROLLED_BACK, 0, false); write(journal); cleanup(journal);
        return "Working-tree restoration rolled back and recovery resources removed";
    }

    private RestoreJournal catalog(RestorePlan plan) throws Exception {
        var observed = new HashMap<String, RestorePlan.ExpectedPath>();
        plan.expectedPaths().forEach(path -> observed.put(path.requestedPath(), path));
        var originals = new TreeMap<String, RestorePlan.ExpectedPath>();
        var targets = new TreeMap<String, String>();
        var destructiveDirs = new ArrayList<String>();
        for (var action : plan.actions()) {
            if (!action.requiresChange()) continue;
            var expected = observed.get(action.path());
            if (expected.type() == RestorePlan.Type.FILE) originals.put(expected.actualPath(), expected);
            if (action.targetHash() != null) {
                targets.put(action.path(), action.targetHash());
                if (expected.type() == RestorePlan.Type.DIRECTORY) destructiveDirs.add(expected.actualPath());
            }
        }
        plan.directoryChanges().forEach(change -> destructiveDirs.add(change.currentPath()));
        var oldDirs = new TreeMap<String, RestorePlan.ExpectedPath>(String.CASE_INSENSITIVE_ORDER);
        for (var path : plan.expectedPaths()) {
            if (path.type() != RestorePlan.Type.DIRECTORY || path.actualPath().isEmpty()) continue;
            if (destructiveDirs.stream().anyMatch(parent -> parent.equalsIgnoreCase(path.actualPath()) || StagingIndex.ancestor(parent, path.actualPath()))) oldDirs.put(path.actualPath(), path);
        }
        var wantedDirs = new TreeSet<String>();
        for (String path : targets.keySet()) for (int depth = 1; depth < RestoreJournal.depth(path); depth++) {
            String prefix = StagingIndex.prefix(path, depth);
            var expected = observed.get(prefix);
            if (oldDirs.containsKey(prefix) || expected == null || expected.type() != RestorePlan.Type.DIRECTORY || !expected.actualPath().equals(prefix)) wantedDirs.add(prefix);
        }
        var originalItems = new ArrayList<Item>();
        for (var entry : originals.entrySet()) {
            files.parents(entry.getKey()); files.noReadOnly(files.work(entry.getKey()));
            originalItems.add(new Item(originalItems.size(), entry.getKey(), entry.getValue().contentHash(), stamp(entry.getValue())));
        }
        var targetItems = new ArrayList<Item>();
        targets.forEach((path, hash) -> targetItems.add(new Item(targetItems.size(), path, hash, null)));
        var dirs = new ArrayList<Directory>();
        for (var entry : oldDirs.entrySet()) {
            files.parents(entry.getKey()); files.noReadOnly(files.work(entry.getKey()));
            String target = wantedDirs.stream().filter(path -> path.equalsIgnoreCase(entry.getKey())).findFirst().orElse(null);
            if (target != null) wantedDirs.remove(target);
            dirs.add(new Directory(dirs.size(), entry.getKey(), target, stamp(entry.getValue())));
        }
        for (String target : wantedDirs) dirs.add(new Directory(dirs.size(), null, target, null));
        var selected = plan.selection();
        var paths = new MetadataPaths(storage);
        return new RestoreJournal(UUID.randomUUID().toString().replace("-", ""), root.toString(), stamp(observed.get("")),
                selected.headId(), selected.target().id(), digest(paths.readBytes("HEAD")), selected.stagingFingerprint(),
                digest(paths.readBytes("snapshots/" + selected.headId() + ".snapshot")), digest(paths.readBytes("snapshots/" + selected.target().id() + ".snapshot")),
                Phase.PREPARING, 0, false, originalItems, targetItems, dirs);
    }

    private static Stamp stamp(RestorePlan.ExpectedPath expected) {
        return new Stamp(expected.created().toString(), expected.modified().toString(), expected.size(), expected.fileKey());
    }

    private void createArea() throws Exception {
        Path area = new MetadataPaths(storage).directory("staging").getParent().resolve("restore-work");
        Files.createDirectory(area);
        for (String bucket : RestoreFileOps.BUCKETS) Files.createDirectory(new MetadataPaths(storage).directory("restore-work").resolve(bucket));
    }

    private RestoreJournal prepare(RestoreJournal journal) throws Exception {
        for (Item original : journal.originals()) files.backup(original);
        var targets = new ArrayList<Item>();
        for (Item item : journal.targets()) {
            Item original = journal.originals().stream().filter(old -> old.path().equalsIgnoreCase(item.path())).findFirst().orElse(null);
            targets.add(files.target(item, original));
        }
        var directories = new ArrayList<Directory>();
        for (Directory dir : journal.directories()) {
            if (dir.original() == null) {
                Path prepared = files.asset("directories", dir.id()); Files.createDirectory(prepared);
                directories.add(new Directory(dir.id(), null, dir.target(), Stamp.of(RestoreFileOps.attributes(prepared))));
            } else directories.add(dir);
        }
        return journal.prepared(targets, directories);
    }

    private void revalidate(RestorePlan plan) throws Exception {
        for (var expected : plan.expectedPaths()) {
            if (expected.requestedPath().isEmpty()) { files.root(stamp(expected)); continue; }
            var current = new WorkingTreePaths(root).observeDestination(expected.requestedPath()).getLast();
            require(current.problem() == null && current.actual().equals(expected.actualPath()), "Path identity changed during preparation: " + expected.requestedPath());
            require(Objects.equals(current.blockingAncestor(), expected.blockingAncestor()), "Ancestor changed before restoration: " + expected.requestedPath());
            var attrs = current.attributes();
            switch (expected.type()) {
                case MISSING -> require(attrs == null, "Destination appeared during preparation: " + expected.requestedPath());
                case FILE -> require(files.file(expected.actualPath(), new Item(0, expected.actualPath(), expected.contentHash(), stamp(expected)), null), "Working file changed during preparation: " + expected.requestedPath());
                case DIRECTORY -> {
                    require(attrs != null && attrs.isDirectory() && stamp(expected).matches(attrs, true), "Directory changed during preparation: " + expected.requestedPath());
                    if (expected.children() != null) {
                        var names = new ArrayList<String>();
                        files.parents(expected.actualPath());
                        try (var entries = Files.newDirectoryStream(files.work(expected.actualPath()))) { for (Path entry : entries) names.add(entry.getFileName().toString()); }
                        Collections.sort(names); require(names.equals(expected.children()), "Directory membership changed: " + expected.requestedPath());
                    }
                }
                case UNSUPPORTED -> throw new IOException("Unsupported restore precondition");
            }
        }
    }

    private void anchor(RestoreJournal journal) throws Exception {
        commitClear(); files.root(journal.rootStamp());
        var paths = new MetadataPaths(storage);
        require(digest(paths.readBytes("HEAD")).equals(journal.headBytesHash())
                && SnapshotHistory.observeIndex(storage).fingerprint().equals(journal.indexHash()), "HEAD or staging changed; recovery state was preserved");
    }

    private void baseline(RestoreJournal journal) throws Exception {
        require(journal.root().equals(root.toString()), "Restoration belongs to a different project root");
        anchor(journal);
        var selection = new SnapshotHistory(storage).selectForRestoreRecovery(journal.target());
        require(selection.headId().equals(journal.head()), "Unexpected HEAD baseline");
        var paths = new MetadataPaths(storage);
        require(digest(paths.readBytes("snapshots/" + journal.head() + ".snapshot")).equals(journal.headManifestHash())
                && digest(paths.readBytes("snapshots/" + journal.target() + ".snapshot")).equals(journal.targetManifestHash()), "Snapshot metadata changed; recovery refused");
        for (Item item : journal.originals()) require(item.hash().equals(selection.head().files().get(item.path())), "Invalid original ownership in journal: " + item.path());
        for (Item item : journal.targets()) require(item.hash().equals(selection.target().files().get(item.path())), "Invalid target mapping in journal: " + item.path());
        for (Directory dir : journal.directories()) {
            if (dir.original() != null) require(selection.head().files().keySet().stream().anyMatch(path -> StagingIndex.ancestor(dir.original(), path)), "Unowned original directory in journal");
            if (dir.target() != null) require(selection.target().files().keySet().stream().anyMatch(path -> path.startsWith(dir.target() + "/")), "Invalid target directory in journal");
        }
    }

    private void commitClear() throws IOException {
        try { SnapshotHistory.requireNoPendingRecovery(storage); }
        catch (IOException failure) { throw new IOException("Commit recovery is pending; neither commit nor restore recovery was performed. Preserve both records and investigate before recovery", failure); }
    }

    private void verifyAssets(RestoreJournal journal) throws Exception {
        inventory(journal);
        for (Item item : journal.originals()) files.verifyHash(files.asset("backup", item.id()), item.hash());
        for (Item item : journal.targets()) files.verifyHash(files.asset("prepared", item.id()), item.hash());
    }

    /** Reject unexpected resources before any recovery mutation, not just at cleanup. */
    private void inventory(RestoreJournal journal) throws Exception {
        Path area = storage.resolve("restore-work");
        require(RestoreFileOps.attributes(area) != null, "Missing restoration work area");
        new MetadataPaths(storage).directory("restore-work");
        try (var entries = Files.newDirectoryStream(area)) {
            for (Path path : entries) require(RestoreFileOps.BUCKETS.contains(path.getFileName().toString()), "Unexpected restore-work entry: " + path.getFileName());
        }
        for (String bucket : RestoreFileOps.BUCKETS) {
            Path parent = new MetadataPaths(storage).directory("restore-work/" + bucket);
            try (var entries = Files.newDirectoryStream(parent)) {
                for (Path path : entries) {
                    String name = path.getFileName().toString();
                    require(name.matches("0|[1-9][0-9]*"), "Unexpected restore asset name");
                    int id = Integer.parseInt(name);
                    int count = bucket.equals("directories") ? journal.directories().size()
                            : bucket.equals("backup") || bucket.equals("original-files") ? journal.originals().size() : journal.targets().size();
                    require(id < count, "Unexpected restore asset reference");
                    Path checked = files.asset(bucket, id);
                    if (bucket.equals("directories")) require(RestoreFileOps.empty(checked), "Unexpected contents in a private restoration directory");
                }
            }
        }
    }

    private boolean before(RestoreJournal j, Step step) throws Exception {
        return switch (step.kind()) {
            case DETACH_FILE -> files.file(step.path(), j.originals().get(step.id()), null) && RestoreFileOps.attributes(files.asset("original-files", step.id())) == null;
            case DETACH_DIRECTORY -> files.directory(step.path(), j.directories().get(step.id()).stamp()) && RestoreFileOps.attributes(files.asset("directories", step.id())) == null;
            case ATTACH_DIRECTORY -> files.missing(step.path()) && files.privateDirectory(j.directories().get(step.id()));
            case PUBLISH_FILE -> files.missing(step.path()) && files.privateFile("prepared", j.targets().get(step.id()));
        };
    }

    private boolean after(RestoreJournal j, Step step) throws Exception {
        return switch (step.kind()) {
            case DETACH_FILE -> files.missing(step.path()) && files.privateFile("original-files", j.originals().get(step.id()));
            case DETACH_DIRECTORY -> files.missing(step.path()) && files.privateDirectory(j.directories().get(step.id()));
            case ATTACH_DIRECTORY -> files.directory(step.path(), j.directories().get(step.id()).stamp()) && RestoreFileOps.attributes(files.asset("directories", step.id())) == null;
            case PUBLISH_FILE -> files.file(step.path(), j.targets().get(step.id()), files.asset("prepared", step.id()));
        };
    }

    private void forward(RestoreJournal j, Step step) throws Exception {
        require(before(j, step), "Execution precondition changed: " + detail(step));
        files.parents(step.path());
        switch (step.kind()) {
            case DETACH_FILE -> { files.noReadOnly(files.work(step.path())); files.rename(files.work(step.path()), files.asset("original-files", step.id())); }
            case DETACH_DIRECTORY -> files.rename(files.work(step.path()), files.asset("directories", step.id()));
            case ATTACH_DIRECTORY -> files.rename(files.asset("directories", step.id()), files.work(step.path()));
            case PUBLISH_FILE -> files.link(files.work(step.path()), files.asset("prepared", step.id()));
        }
    }

    private boolean undone(RestoreJournal j, Step step) throws Exception {
        return switch (step.kind()) {
            case DETACH_FILE -> files.privateFile("original-files", j.originals().get(step.id()))
                    && files.file(step.path(), j.originals().get(step.id()), files.asset("original-files", step.id()));
            case PUBLISH_FILE -> files.missing(step.path()) && files.privateFile("undo", j.targets().get(step.id()))
                    && Files.isSameFile(files.asset("undo", step.id()), files.asset("prepared", step.id()));
            case DETACH_DIRECTORY, ATTACH_DIRECTORY -> before(j, step);
        };
    }

    private void undo(RestoreJournal j, Step step) throws Exception {
        files.parents(step.path());
        switch (step.kind()) {
            case DETACH_FILE -> files.link(files.work(step.path()), files.asset("original-files", step.id()));
            case DETACH_DIRECTORY -> files.rename(files.asset("directories", step.id()), files.work(step.path()));
            case ATTACH_DIRECTORY -> files.rename(files.work(step.path()), files.asset("directories", step.id()));
            case PUBLISH_FILE -> files.rename(files.work(step.path()), files.asset("undo", step.id()));
        }
    }

    private void verifyTarget(Snapshot target, Snapshot head) throws Exception {
        var identity = new WorkingTreePaths(root);
        for (var entry : target.files().entrySet()) {
            Path actual = identity.trackedFile(entry.getKey());
            require(actual != null && actual.toString().replace('\\', '/').equals(entry.getKey())
                    && new FileHasher().hash(root.resolve(actual)).equals(entry.getValue()), "Final target verification failed: " + entry.getKey());
        }
        for (String path : head.files().keySet()) {
            if (target.files().keySet().stream().anyMatch(targetPath -> targetPath.equalsIgnoreCase(path))) continue;
            require(identity.trackedFile(path) == null, "Unexpected remaining HEAD-only file: " + path);
        }
    }

    private void verifyOriginals(RestoreJournal journal) throws Exception {
        for (Item item : journal.originals()) {
            Path displaced = files.asset("original-files", item.id());
            require(files.file(item.path(), item, RestoreFileOps.attributes(displaced) == null ? null : displaced), "Original file not recovered: " + item.path());
        }
        for (Directory dir : journal.directories()) if (dir.original() != null) {
            var attrs = files.probe(dir.original()).attributes();
            require(attrs != null && attrs.isDirectory() && dir.stamp().matches(attrs, true), "Original directory not recovered: " + dir.original());
        }
        for (Item target : journal.targets()) {
            boolean replaced = journal.originals().stream().anyMatch(item -> item.path().equalsIgnoreCase(target.path()))
                    || journal.directories().stream().anyMatch(dir -> dir.original() != null && dir.original().equalsIgnoreCase(target.path()));
            if (!replaced) require(new WorkingTreePaths(root).trackedFile(target.path()) == null, "Created destination remains after rollback: " + target.path());
        }
    }

    private void write(RestoreJournal journal) throws Exception {
        files.checkpoint(JOURNAL_WRITE, journal.phase() + ":" + journal.cursor() + ":" + journal.inFlight());
        files.replace(new MetadataPaths(storage).file("restore.pending", false), journal.encode());
    }

    private RestoreJournal read() throws Exception {
        try { return RestoreJournal.decode(new MetadataPaths(storage).readLines("restore.pending")); }
        catch (NoSuchFileException absent) { return null; }
    }

    private void cleanup(RestoreJournal journal) throws Exception {
        require(journal.finished(), "Cannot clean an unfinished restore journal");
        files.checkpoint(CLEANUP, journal.phase().toString());
        Path area = storage.resolve("restore-work");
        if (RestoreFileOps.attributes(area) != null) {
            new MetadataPaths(storage).directory("restore-work");
            var deletions = new ArrayList<Path>();
            var presentBuckets = new ArrayList<Path>();
            try (var entries = Files.newDirectoryStream(area)) {
                for (Path entry : entries) require(RestoreFileOps.BUCKETS.contains(entry.getFileName().toString()), "Unexpected restore-work entry: " + entry.getFileName());
            }
            for (String bucket : RestoreFileOps.BUCKETS) {
                Path parent = area.resolve(bucket);
                if (RestoreFileOps.attributes(parent) == null) continue;
                new MetadataPaths(storage).directory("restore-work/" + bucket);
                presentBuckets.add(parent);
                try (var entries = Files.newDirectoryStream(parent)) {
                    for (Path entry : entries) {
                        String name = entry.getFileName().toString();
                        require(name.matches("0|[1-9][0-9]*"), "Unexpected restore asset name");
                        int id = Integer.parseInt(name);
                        if (bucket.equals("directories")) {
                            require(id < journal.directories().size(), "Unexpected directory asset");
                            Path checked = files.asset(bucket, id); var attrs = RestoreFileOps.attributes(checked);
                            var stamp = journal.directories().get(id).stamp();
                            require(RestoreFileOps.empty(checked) && (journal.phase() == Phase.ABORTED || stamp.matches(attrs, true)), "Unexpected private directory contents or identity");
                        } else {
                            List<Item> items = bucket.equals("backup") || bucket.equals("original-files") ? journal.originals() : journal.targets();
                            require(id < items.size(), "Unexpected file asset");
                            Path checked = files.asset(bucket, id); Item item = items.get(id);
                            if (journal.phase() != Phase.ABORTED) {
                                boolean linkedFinished = (journal.phase() == Phase.COMPLETE && (bucket.equals("prepared") || bucket.equals("undo")))
                                        || (journal.phase() == Phase.ROLLED_BACK && bucket.equals("original-files"));
                                boolean same = false;
                                if (linkedFinished) {
                                    var observed = files.probe(item.path()).attributes();
                                    same = observed != null && observed.isRegularFile() && Files.isSameFile(files.work(item.path()), checked);
                                }
                                if (!same) files.verifyHash(checked, item.hash());
                            }
                        }
                        deletions.add(entry);
                    }
                }
            }
            for (Path path : deletions) {
                String bucket = path.getParent().getFileName().toString(); int id = Integer.parseInt(path.getFileName().toString());
                files.checkpoint(CLEANUP, bucket + "/" + id); Files.delete(files.asset(bucket, id));
            }
            for (Path bucket : presentBuckets) {
                files.checkpoint(CLEANUP, bucket.getFileName().toString());
                Files.delete(new MetadataPaths(storage).directory("restore-work/" + bucket.getFileName()));
            }
            files.checkpoint(CLEANUP, "restore-work"); Files.delete(new MetadataPaths(storage).directory("restore-work"));
        }
        files.checkpoint(CLEANUP, "restore.pending");
        Files.delete(new MetadataPaths(storage).file("restore.pending", true)); // Always LAST.
    }

    private String bootstrapCleanup() throws Exception {
        Path area = storage.resolve("restore-work");
        if (RestoreFileOps.attributes(area) == null) return "No pending restoration";
        new MetadataPaths(storage).directory("restore-work");
        var buckets = new ArrayList<Path>();
        try (var entries = Files.newDirectoryStream(area)) {
            for (Path path : entries) {
                require(RestoreFileOps.BUCKETS.contains(path.getFileName().toString()), "Missing journal with unexpected assets; manual investigation required");
                new MetadataPaths(storage).directory("restore-work/" + path.getFileName());
                require(RestoreFileOps.empty(path), "Missing journal with restoration data; preserve assets and investigate manually");
                buckets.add(path);
            }
        }
        for (Path bucket : buckets) Files.delete(bucket);
        Files.delete(area);
        return "Empty unpublished preparation area removed; no working restoration was started";
    }

    private static void require(boolean condition, String reason) throws IOException { if (!condition) throw new IOException(reason); }
    private static String detail(Step step) { return step.kind() + ":" + step.path(); }
}
