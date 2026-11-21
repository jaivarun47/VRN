package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Packaged-CLI regressions for MT-001 and MT-002, with no dependency on user repositories. */
class AddIgnoreRegressionTest {
    @TempDir Path temporary;
    Path root;
    Path storage;
    StagingArea staging;

    @BeforeEach void initialize() throws Exception {
        root = Files.createDirectory(temporary.resolve("project"));
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        staging = new StagingArea(storage);
        select("notes.txt", "original notes");
        select("temp/tracked.txt", "original tracked contents");
        stage(".vrnignore");
        new CommitService(storage).commit("fixture");
    }

    @ParameterizedTest @CsvSource({"notes.txt,unused", "notes.txt,notes.txt", "temp/tracked.txt,temp/", "notes.txt,*.txt"})
    void trackedModifiedFileCanBeExplicitlySelectedDespiteIgnoreRule(String path, String rule) throws Exception {
        Files.writeString(root.resolve(path), "selected current contents");
        rules(rule);
        var before = WorkingTreeStatus.compute(root);
        assertTrue(before.modifiedNotStaged.contains(path));
        String beforeCli = success("status");
        assertTrue(beforeCli.contains(path), beforeCli);
        String addOutput = success("add", path);
        String afterCli = success("status");
        System.out.println("MT-001 before add:\n" + beforeCli + "add output:\n" + addOutput + "after add:\n" + afterCli);
        assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest("selected current contents")), staging.readOperations().get(path));
        var selected = WorkingTreeStatus.compute(root);
        assertTrue(selected.stagedModifications.contains(path));
        assertFalse(selected.modifiedNotStaged.contains(path));
        assertTrue(selected.modifiedNotStaged.contains(".vrnignore"));
        Files.writeString(root.resolve(path), "edited after staging");
        assertTrue(WorkingTreeStatus.compute(root).modifiedNotStaged.contains(path));
        var intent = staging.readOperations();
        rules("unused");
        assertEquals(intent, staging.readOperations());
        assertTrue(WorkingTreeStatus.compute(root).stagedModifications.contains(path));
        success("unstage", path);
        assertFalse(staging.readOperations().containsKey(path));
        assertTrue(WorkingTreeStatus.compute(root).modifiedNotStaged.contains(path));
        assertEquals("edited after staging", Files.readString(root.resolve(path)));
    }

    @Test void reportedTrackedDisappearanceDoesNotOccurOnCurrentCheckout() throws Exception {
        Files.writeString(root.resolve("notes.txt"), "modified");
        rules("temp/\nnotes.txt");
        assertTrue(WorkingTreeStatus.compute(root).modifiedNotStaged.contains("notes.txt"));
        success("add", "notes.txt");
        var status = WorkingTreeStatus.compute(root);
        System.out.println("MT-001 visibility: staged=" + status.stagedModifications + "; unstaged=" + status.modifiedNotStaged);
        assertTrue(status.stagedModifications.contains("notes.txt") || status.modifiedNotStaged.contains("notes.txt"));
        assertFalse(status.isClean());
    }

    @ParameterizedTest @ValueSource(strings = {"temp/", "cache.txt"})
    void explicitIgnoredUntrackedFileIsRejectedTruthfullyWithoutChangingIntent(String rule) throws Exception {
        Files.writeString(root.resolve("temp/cache.txt"), "ignored cache");
        staging.stageDelete(Path.of("notes.txt"));
        select("pending.txt", "selected new file");
        byte[] before = Files.readAllBytes(storage.resolve("staging/index"));
        rules(rule);
        Run add = run("add", "temp/cache.txt");
        System.out.println("MT-002 exit=" + add.code() + " output:\n" + add.output());
        assertEquals(1, add.code(), add.output());
        assertTrue(add.output().toLowerCase(java.util.Locale.ROOT).contains("ignored"), add.output());
        assertFalse(add.output().contains("Files staged successfully"));
        assertArrayEquals(before, Files.readAllBytes(storage.resolve("staging/index")));
        assertEquals("ignored cache", Files.readString(root.resolve("temp/cache.txt")));
        assertFalse(WorkingTreeStatus.compute(root).untracked.contains("temp/cache.txt"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stagedOnlyAdditionRemainsExplicitlyUpdatableAfterIgnoreChanges(boolean directoryRule) throws Exception {
        select("temp/new.txt", "first selected");
        rules(directoryRule ? "temp/" : "new.txt");
        Files.writeString(root.resolve("temp/new.txt"), "second selection");
        success("add", "temp/new.txt");
        assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest("second selection")), staging.readOperations().get("temp/new.txt"));
        assertTrue(WorkingTreeStatus.compute(root).stagedNewFiles.contains("temp/new.txt"));
        assertFalse(WorkingTreeStatus.compute(root).modifiedNotStaged.contains("temp/new.txt"));
    }

    @ParameterizedTest @ValueSource(strings = {".", "temp"})
    void directorySelectionIncludesKnownFilesButKeepsIgnoredUntrackedFilesExcluded(String target) throws Exception {
        select("temp/new.txt", "selected before ignore");
        staging.stageDelete(Path.of("notes.txt"));
        Files.writeString(root.resolve("notes.txt"), "current notes");
        Files.writeString(root.resolve("temp/tracked.txt"), "current tracked");
        Files.writeString(root.resolve("temp/new.txt"), "current staged addition");
        Files.writeString(root.resolve("temp/cache.txt"), "ignored untracked");
        rules("temp/\nnotes.txt");
        success("add", target);
        var intent = staging.readOperations();
        assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest("current tracked")), intent.get("temp/tracked.txt"));
        assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest("current staged addition")), intent.get("temp/new.txt"));
        assertFalse(intent.containsKey("temp/cache.txt"));
        if (target.equals("temp")) assertInstanceOf(StagedOperation.Delete.class, intent.get("notes.txt"));
        else assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest("current notes")), intent.get("notes.txt"));
    }

    @Test void ignoredTrackedPutDeleteAndUnstageStillUseTheSameIntentModel() throws Exception {
        rules("notes.txt");
        success("stage-delete", "notes.txt");
        assertInstanceOf(StagedOperation.Delete.class, staging.readOperations().get("notes.txt"));
        Files.writeString(root.resolve("notes.txt"), "replacement");
        success("add", "notes.txt");
        assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest("replacement")), staging.readOperations().get("notes.txt"));
        success("unstage", "notes.txt");
        assertTrue(WorkingTreeStatus.compute(root).modifiedNotStaged.contains("notes.txt"));
        success("stage-delete", "notes.txt");
        success("unstage", "notes.txt");
        assertTrue(staging.readOperations().isEmpty());
        assertEquals("replacement", Files.readString(root.resolve("notes.txt")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void emptyOrEntirelyIgnoredUntrackedDirectoryIsATruthfulNoOp(boolean ignored) throws Exception {
        Path directory = Files.createDirectory(root.resolve("scratch"));
        if (ignored) {
            Files.writeString(directory.resolve("cache.txt"), "ignored");
            rules("scratch/");
        }
        select("pending.txt", "pending");
        byte[] before = Files.readAllBytes(storage.resolve("staging/index"));
        Run result = run("add", "scratch");
        assertEquals(0, result.code(), result.output());
        assertTrue(result.output().contains("No eligible files"), result.output());
        assertFalse(result.output().contains("Files staged successfully"));
        assertArrayEquals(before, Files.readAllBytes(storage.resolve("staging/index")));
    }

    @Test void ignoreFileItselfRemainsSelectableWhenAlreadyTrackedAndIgnored() throws Exception {
        rules("*");
        success("add", ".vrnignore");
        assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest(".vrn/\n*\n")), staging.readOperations().get(".vrnignore"));
    }

    @Test void rootInternalsCannotBeSelectedAfterIgnoringAllFiles() throws Exception {
        Path automatic = Files.createDirectories(root.resolve(".vrnauto/objects"));
        Files.writeString(automatic.resolve("private"), "private automatic state");
        rules("*");
        var before = RepositoryPreflightTest.tree(storage);
        var autoBefore = RepositoryPreflightTest.tree(root.resolve(".vrnauto"));
        for (String path : List.of(".vrn", ".vrn/HEAD", ".vrnauto", ".vrnauto/objects/private")) {
            assertEquals(1, run("add", path).code());
            assertEquals(before, RepositoryPreflightTest.tree(storage));
        }
        success("add", ".");
        assertTrue(staging.readOperations().keySet().stream().noneMatch(path -> path.startsWith(".vrn/") || path.startsWith(".vrnauto/")));
        assertEquals(autoBefore, RepositoryPreflightTest.tree(root.resolve(".vrnauto")));
    }

    @Test void successfulTrackedSelectionCommitsCurrentBytesWithoutChangingOlderSnapshot() throws Exception {
        String parent = Files.readString(storage.resolve("HEAD"));
        byte[] original = Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot"));
        Files.writeString(root.resolve("notes.txt"), "selected for commit");
        rules("notes.txt");
        success("add", "notes.txt");
        success("commit", "-m", "capture tracked ignored contents");
        var next = new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD")));
        assertEquals(StatusCorrectnessTest.digest("selected for commit"), next.files().get("notes.txt"));
        assertArrayEquals(original, Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot")));
    }

    void rules(String rule) throws Exception { Files.writeString(root.resolve(".vrnignore"), ".vrn/\n" + rule + "\n"); }

    void select(String path, String contents) throws Exception {
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), contents);
        stage(path);
    }

    void stage(String path) throws Exception {
        var file = new FileHasher().hashWithBytes(root.resolve(path));
        new ObjectStore(storage).store(file.hash(), file.bytes());
        staging.stage(Path.of(path), file.hash());
    }

    String success(String... arguments) throws Exception {
        Run result = run(arguments);
        assertEquals(0, result.code(), result.output());
        return result.output();
    }

    Run run(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-jar", System.getProperty("vrn.executableJar")));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(temporary, "cli-", ".txt");
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI timeout");
            return new Run(process.exitValue(), Files.readString(output));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    record Run(int code, String output) {}
}
