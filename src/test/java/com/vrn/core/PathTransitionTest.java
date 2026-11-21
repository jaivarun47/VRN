package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

import static org.junit.jupiter.api.Assertions.*;

class PathTransitionTest {
    @TempDir Path root;
    Path storage;
    Path index;
    String parent;
    Map<String, String> original;
    byte[] originalSnapshot;

    @ParameterizedTest @CsvSource({
            "file-case,HELLO.TXT", "file-case,.", "dir-case,SRC", "dir-case,.",
            "file-dir,config", "file-dir,.", "dir-file,assets", "dir-file,."})
    void selectedTransitionPublishesCompleteIntentAndPreservesHistory(String kind, String selection) throws Exception {
        prepare(kind);
        var before = WorkingTreeStatus.compute(root);
        if (kind.endsWith("case")) assertFalse(before.caseOnlyChanges.isEmpty(), "Case changes must be visible before staging");
        if (kind.equals("file-dir") || kind.equals("dir-case")) {
            var existing = new StagingArea(storage).readOperations();
            String affected = oldPaths(kind).getFirst();
            existing.put(affected, selection.equals(".") ? new StagedOperation.Delete()
                    : new StagedOperation.Put(original.get(affected)));
            Files.writeString(index, MetadataCodec.encodeOperations(existing));
        }
        new AddService(root).add(Path.of(selection));
        Map<String, StagedOperation> operations = new StagingArea(storage).readOperations();
        for (String old : oldPaths(kind)) assertInstanceOf(StagedOperation.Delete.class, operations.get(old), old);
        for (String actual : newPaths(kind)) assertEquals(new StagedOperation.Put(new FileHasher().hash(root.resolve(actual))), operations.get(actual));
        assertInstanceOf(StagedOperation.Put.class, operations.get("pending.txt"));
        assertInstanceOf(StagedOperation.Delete.class, operations.get("obsolete.txt"));
        var selected = WorkingTreeStatus.compute(root);
        assertTrue(selected.stagedDeletions.containsAll(oldPaths(kind)));
        assertTrue(selected.stagedNewFiles.containsAll(newPaths(kind)));
        assertTrue(selected.caseOnlyChanges.isEmpty());
        String next = new CommitService(storage).commit("structural transition").snapshotId();
        var snapshot = new SnapshotStore(storage).load(next);
        var expected = new TreeSet<>(original.keySet());
        expected.removeAll(oldPaths(kind));
        expected.remove("obsolete.txt");
        expected.addAll(newPaths(kind));
        expected.add("pending.txt");
        assertEquals(expected, new TreeSet<>(snapshot.files().keySet()));
        assertEquals(parent, snapshot.parentId());
        assertArrayEquals(originalSnapshot, Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot")));
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @ParameterizedTest @ValueSource(strings = {"SRC/Main.java", "SRC/nested"})
    void descendantSelectionCannotPartiallyRenameDirectory(String selection) throws Exception {
        prepare("dir-case");
        byte[] before = Files.readAllBytes(index);
        Exception failure = assertThrows(IOException.class, () -> new AddService(root).add(Path.of(selection)));
        assertTrue(failure.getMessage().contains("complete transition"));
        assertArrayEquals(before, Files.readAllBytes(index));
        new AddService(root).add(Path.of("SRC"));
        assertTrue(new StagingArea(storage).readOperations().containsKey("SRC/nested/Utils.java"));
    }

    @ParameterizedTest @ValueSource(strings = {"file-case", "dir-case", "file-dir", "dir-file"})
    void individualUnstageCancelsPutSidesFirstAndPreservesUnrelatedIntent(String kind) throws Exception {
        prepare(kind);
        new AddService(root).add(Path.of(selection(kind)));
        var staging = new StagingArea(storage);
        byte[] before = Files.readAllBytes(index);
        assertThrows(IOException.class, () -> staging.unstage(Path.of(oldPaths(kind).getFirst())));
        assertArrayEquals(before, Files.readAllBytes(index));
        for (String path : newPaths(kind)) assertTrue(staging.unstage(Path.of(path)));
        for (String path : oldPaths(kind)) assertTrue(staging.unstage(Path.of(path)));
        assertEquals(Set.of("pending.txt", "obsolete.txt"), staging.readOperations().keySet());
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        for (String path : newPaths(kind)) assertTrue(Files.isRegularFile(root.resolve(path)));
        new AddService(root).add(Path.of(selection(kind)));
        new CommitService(storage).commit("reselected transition");
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @Test void nestedDirectoryComponentsAreRecordedWithTheirActualCapitalization() throws Exception {
        prepare("dir-case");
        rename("SRC/nested", "SRC/NESTED");
        rename("SRC/NESTED/Utils.java", "SRC/NESTED/UTILS.JAVA");
        write("SRC/NESTED/UTILS.JAVA", "updated during multiple component rename");
        new AddService(root).add(Path.of("SRC"));
        String next = new CommitService(storage).commit("multiple components").snapshotId();
        var paths = new SnapshotStore(storage).load(next).files().keySet();
        assertTrue(paths.contains("SRC/NESTED/UTILS.JAVA"));
        assertFalse(paths.contains("src/nested/Utils.java"));
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @Test void cancellingOneDirectoryPutCannotReintroduceAMixedSpellingTree() throws Exception {
        prepare("dir-case");
        new AddService(root).add(Path.of("SRC"));
        var staging = new StagingArea(storage);
        staging.unstage(Path.of("SRC/Main.java"));
        byte[] before = Files.readAllBytes(index);
        assertThrows(IOException.class, () -> staging.unstage(Path.of("src/Main.java")));
        assertArrayEquals(before, Files.readAllBytes(index));
        assertTrue(staging.readOperations().containsKey("SRC/nested/Utils.java"));
    }

    @ParameterizedTest @ValueSource(strings = {"file-dir", "dir-file"})
    void caseAndTypeChangesCanBeCombinedAtTheSameComponent(String kind) throws Exception {
        prepare(kind);
        String old = selection(kind);
        String actual = old.toUpperCase(java.util.Locale.ROOT);
        rename(old, actual);
        new AddService(root).add(Path.of(actual));
        String next = new CommitService(storage).commit("combined case and type transition").snapshotId();
        var paths = new SnapshotStore(storage).load(next).files().keySet();
        for (String previous : oldPaths(kind)) assertFalse(paths.contains(previous));
        for (String current : newPaths(kind)) assertTrue(paths.contains(actual + current.substring(old.length())));
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @Test void unicodeSpacesAndPunctuationRemainExactThroughCaseTransition() throws Exception {
        prepare("file-case");
        new AddService(root).add(Path.of("HELLO.TXT"));
        new CommitService(storage).commit("first case transition");
        String old = "Sp ace=é#notes.txt";
        String actual = "SP ACE=É#NOTES.TXT";
        write(old, "日本語 contents");
        new AddService(root).add(Path.of(old));
        new CommitService(storage).commit("unicode original");
        rename(old, actual);
        new AddService(root).add(Path.of(actual));
        String next = new CommitService(storage).commit("unicode case transition").snapshotId();
        var paths = new SnapshotStore(storage).load(next).files().keySet();
        assertTrue(paths.contains(actual));
        assertFalse(paths.contains(old));
    }

    @Test void ignoredTrackedDirectoryDescendantsAreIncludedInTheCompleteRename() throws Exception {
        prepare("dir-case");
        write(".vrnignore", "SRC/\n");
        write("SRC/ignored-new.txt", "ignored untracked");
        new AddService(root).add(Path.of("SRC"));
        var operations = new StagingArea(storage).readOperations();
        for (String path : newPaths("dir-case")) assertInstanceOf(StagedOperation.Put.class, operations.get(path));
        assertFalse(operations.containsKey("SRC/ignored-new.txt"));
    }

    @Test void componentBoundariesDoNotTreatAnUnrelatedPrefixAsReplacement() throws Exception {
        prepare("dir-file");
        write("assets-more", "unrelated file");
        new AddService(root).add(Path.of("assets-more"));
        var operations = new StagingArea(storage).readOperations();
        for (String old : oldPaths("dir-file")) assertFalse(operations.containsKey(old));
    }

    @Test void missingDescendantRequiresExplicitDeletionBeforeDirectoryRename() throws Exception {
        prepare("dir-case");
        Files.delete(root.resolve("SRC/nested/Utils.java"));
        byte[] before = Files.readAllBytes(index);
        assertThrows(IOException.class, () -> new AddService(root).add(Path.of("SRC")));
        assertArrayEquals(before, Files.readAllBytes(index));
        new StagingArea(storage).stageDelete(Path.of("src/nested/Utils.java"));
        new AddService(root).add(Path.of("SRC"));
        String next = new CommitService(storage).commit("explicit missing descendant").snapshotId();
        assertFalse(new SnapshotStore(storage).load(next).files().containsKey("SRC/nested/Utils.java"));
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @Test void individualReplacementDescendantDeletesOnlyItsNecessaryObstruction() throws Exception {
        prepare("file-dir");
        Files.delete(root.resolve("keep.txt"));
        new AddService(root).add(Path.of("config/settings.json"));
        var operations = new StagingArea(storage).readOperations();
        assertInstanceOf(StagedOperation.Delete.class, operations.get("config"));
        assertFalse(operations.containsKey("keep.txt"));
        assertFalse(operations.containsKey("config/nested/defaults.json"));
        String next = new CommitService(storage).commit("selected descendant only").snapshotId();
        assertTrue(new SnapshotStore(storage).load(next).files().containsKey("keep.txt"));
    }

    @Test void ignoredUntrackedReplacementChildrenAreExcludedButTrackedRenameChildrenRemainEligible() throws Exception {
        prepare("file-dir");
        write(".vrnignore", "config/nested/\n");
        new AddService(root).add(Path.of("config"));
        assertFalse(new StagingArea(storage).readOperations().containsKey("config/nested/defaults.json"));
        assertTrue(new StagingArea(storage).readOperations().containsKey("config/settings.json"));
    }

    @Test void directoryToFileCancelsOnlyObstructingStagedDescendants() throws Exception {
        prepare("dir-file");
        String hash = new FileHasher().hash(root.resolve("keep.txt"));
        var entries = new StagingArea(storage).readOperations();
        entries.put("assets/staged-only.txt", new StagedOperation.Put(hash));
        entries.put("assets/logo.png", new StagedOperation.Put(hash));
        entries.put("assets/icons/menu.svg", new StagedOperation.Delete());
        Files.writeString(index, MetadataCodec.encodeOperations(entries));
        new AddService(root).add(Path.of("assets"));
        entries = new StagingArea(storage).readOperations();
        assertFalse(entries.containsKey("assets/staged-only.txt"));
        assertInstanceOf(StagedOperation.Delete.class, entries.get("assets/logo.png"));
        assertInstanceOf(StagedOperation.Delete.class, entries.get("assets/icons/menu.svg"));
        assertInstanceOf(StagedOperation.Put.class, entries.get("pending.txt"));
        assertInstanceOf(StagedOperation.Delete.class, entries.get("obsolete.txt"));
    }

    @ParameterizedTest @ValueSource(strings = {"hello.txt", "Hello.TxT"})
    void repeatedFileCaseTransitionsReuseObjectsAndPreserveSpelling(String finalName) throws Exception {
        prepare("file-case");
        new AddService(root).add(Path.of("HELLO.TXT"));
        new CommitService(storage).commit("first rename");
        String hash = new FileHasher().hash(root.resolve("HELLO.TXT"));
        var before = Files.getLastModifiedTime(storage.resolve("objects/" + hash));
        rename("HELLO.TXT", finalName);
        new AddService(root).add(Path.of(finalName));
        new AddService(root).add(Path.of(finalName));
        assertEquals(before, Files.getLastModifiedTime(storage.resolve("objects/" + hash)));
        String next = new CommitService(storage).commit("second rename").snapshotId();
        assertTrue(new SnapshotStore(storage).load(next).files().containsKey(finalName));
        assertFalse(new SnapshotStore(storage).load(next).files().containsKey("HELLO.TXT"));
    }

    static Stream<Arguments> failures() {
        return Stream.of("file-case", "dir-case", "file-dir", "dir-file").flatMap(kind ->
                Stream.of("discovery", "capture", "temporary", "object", "candidate", "index-write", "index-move")
                        .map(point -> Arguments.of(kind, point)));
    }

    @ParameterizedTest @MethodSource("failures")
    void failedTransitionNeverPublishesPartialIntent(String kind, String point) throws Exception {
        prepare(kind);
        for (String path : newPaths(kind)) write(path, "fresh selected bytes " + path);
        if (point.equals("candidate")) {
            var entries = new StagingArea(storage).readOperations();
            entries.put(".vrnignore/child", new StagedOperation.Put(new FileHasher().hash(root.resolve("keep.txt"))));
            Files.writeString(index, MetadataCodec.encodeOperations(entries));
        }
        byte[] baseline = Files.readAllBytes(index);
        var intent = new StagingArea(storage).readOperations();
        FileScanner scanner = new FileScanner() {
            @Override List<SelectedFile> selectForAddLocked(Path project, Path target, Set<String> known) throws Exception {
                var selected = super.selectForAddLocked(project, target, known);
                if (point.equals("discovery")) throw new IOException("injected discovery failure");
                return selected;
            }
        };
        ContentCapture capture = new ContentCapture() {
            int current;
            int reads;
            @Override String capture(Path project, SelectedFile file, Path temp) throws Exception {
                current++;
                reads = 0;
                return super.capture(project, file, temp);
            }
            @Override protected int read(InputStream input, byte[] bytes) throws IOException {
                if (point.equals("capture") && current == newPaths(kind).size() && ++reads == 2) throw new IOException("injected descendant read failure");
                return super.read(input, bytes);
            }
            @Override protected void write(OutputStream output, byte[] bytes, int length) throws IOException {
                if (point.equals("temporary")) throw new IOException("injected temporary object write failure");
                super.write(output, bytes, length);
            }
        };
        AtomicFilePublisher objectPublisher = new AtomicFilePublisher() {
            @Override protected void linkObject(Path temp, Path destination) throws IOException {
                if (point.equals("object")) throw new IOException("injected content publication failure");
                super.linkObject(temp, destination);
            }
        };
        AtomicFilePublisher indexPublisher = new AtomicFilePublisher() {
            @Override protected void write(Path temp, byte[] bytes) throws IOException {
                if (point.equals("index-write")) throw new IOException("injected index write failure");
                super.write(temp, bytes);
            }
            @Override protected void move(Path temp, Path destination) throws IOException {
                if (point.equals("index-move")) throw new IOException("injected index move failure");
                super.move(temp, destination);
            }
        };
        var service = new AddService(root, scanner, new ObjectStore(storage, objectPublisher, capture), indexPublisher);
        Exception failure = assertThrows(IOException.class, () -> service.add(Path.of(selection(kind))));
        assertTrue(failure.getMessage().contains("no partial staging-index update was published"));
        assertArrayEquals(baseline, Files.readAllBytes(index));
        assertEquals(intent, new StagingArea(storage).readOperations());
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        assertArrayEquals(originalSnapshot, Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot")));
        try (var files = Files.list(storage.resolve("objects"))) {
            for (Path object : files.toList()) new ObjectStore(storage).verify(object.getFileName().toString());
        }
    }

    String selection(String kind) { return switch (kind) { case "file-case" -> "HELLO.TXT"; case "dir-case" -> "SRC"; case "file-dir" -> "config"; default -> "assets"; }; }

    @Test void stagedOnlyFileCanBecomeDirectoryWithoutInventingDelete() throws Exception {
        prepare("file-case");
        new AddService(root).add(Path.of("HELLO.TXT"));
        new CommitService(storage).commit("finish initial transition");
        write("scratch", "staged-only file");
        new AddService(root).add(Path.of("scratch"));
        Files.delete(root.resolve("scratch"));
        write("scratch/child.txt", "replacement child");
        new AddService(root).add(Path.of("scratch"));
        var operations = new StagingArea(storage).readOperations();
        assertEquals(Set.of("scratch/child.txt"), operations.keySet());
        String next = new CommitService(storage).commit("staged-only type change").snapshotId();
        assertTrue(new SnapshotStore(storage).load(next).files().containsKey("scratch/child.txt"));
    }

    @Test void stagedOnlyDescendantParticipatesInDirectoryCaseTransition() throws Exception {
        prepare("dir-case");
        write("SRC/new.txt", "staged-only descendant");
        String hash = StatusCorrectnessTest.digest("staged-only descendant");
        new ObjectStore(storage).store(hash, "staged-only descendant".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var existing = new StagingArea(storage).readOperations();
        existing.put("src/new.txt", new StagedOperation.Put(hash));
        Files.writeString(index, MetadataCodec.encodeOperations(existing));
        new AddService(root).add(Path.of("SRC"));
        var operations = new StagingArea(storage).readOperations();
        assertFalse(operations.containsKey("src/new.txt"));
        assertEquals(new StagedOperation.Put(hash), operations.get("SRC/new.txt"));
        String next = new CommitService(storage).commit("staged-only directory descendant").snapshotId();
        assertTrue(new SnapshotStore(storage).load(next).files().containsKey("SRC/new.txt"));
    }

    void prepare(String kind) throws Exception {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        index = storage.resolve("staging/index");
        write("keep.txt", "keep");
        write("obsolete.txt", "obsolete");
        if (kind.equals("file-case")) write("hello.txt", "same bytes");
        if (kind.equals("dir-case")) { write("src/Main.java", "main"); write("src/nested/Utils.java", "utils"); }
        if (kind.equals("file-dir")) write("config", "old config file");
        if (kind.equals("dir-file")) { write("assets/logo.png", "logo"); write("assets/icons/menu.svg", "menu"); }
        new AddService(root).add(Path.of("."));
        parent = new CommitService(storage).commit("original").snapshotId();
        original = new SnapshotStore(storage).load(parent).files();
        originalSnapshot = Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot"));
        write("pending.txt", "unrelated selected content");
        new AddService(root).add(Path.of("pending.txt"));
        new StagingArea(storage).stageDelete(Path.of("obsolete.txt"));
        Files.delete(root.resolve("obsolete.txt"));
        if (kind.equals("file-case")) rename("hello.txt", "HELLO.TXT");
        if (kind.equals("dir-case")) rename("src", "SRC");
        if (kind.equals("file-dir")) { Files.delete(root.resolve("config")); write("config/settings.json", "settings"); write("config/nested/defaults.json", "defaults"); }
        if (kind.equals("dir-file")) {
            Files.delete(root.resolve("assets/icons/menu.svg")); Files.delete(root.resolve("assets/icons"));
            Files.delete(root.resolve("assets/logo.png")); Files.delete(root.resolve("assets"));
            write("assets", "replacement file");
        }
    }
    List<String> oldPaths(String kind) {
        return switch (kind) { case "file-case" -> List.of("hello.txt"); case "dir-case" -> List.of("src/Main.java", "src/nested/Utils.java"); case "file-dir" -> List.of("config"); default -> List.of("assets/logo.png", "assets/icons/menu.svg"); };
    }
    List<String> newPaths(String kind) {
        return switch (kind) { case "file-case" -> List.of("HELLO.TXT"); case "dir-case" -> List.of("SRC/Main.java", "SRC/nested/Utils.java"); case "file-dir" -> List.of("config/settings.json", "config/nested/defaults.json"); default -> List.of("assets"); };
    }
    void write(String path, String text) throws Exception { Files.createDirectories(root.resolve(path).getParent()); Files.writeString(root.resolve(path), text); }
    void rename(String old, String actual) throws Exception { WindowsStatusIdentityTest.rename(root.resolve(old), root.resolve(actual)); }
}
