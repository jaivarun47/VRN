package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** These packaged-CLI regressions compile and fail against the pre-8A implementation. */
class DeletionStagingRegressionTest {
    @TempDir Path temporary;
    Path root;
    Path storage;
    String parent;

    @BeforeEach void initialize() throws Exception {
        root = Files.createDirectory(temporary.resolve("project"));
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        Files.writeString(root.resolve("old.txt"), "original");
        stage("old.txt");
        stage(".vrnignore");
        parent = new CommitService(storage).commit("parent").snapshotId();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deletionChangesOnlyNextSnapshotAndNeverRemovesWorkingFile(boolean missing) throws Exception {
        Path original = storage.resolve("snapshots/" + parent + ".snapshot");
        byte[] oldSnapshot = Files.readAllBytes(original);
        var objects = RepositoryPreflightTest.tree(storage.resolve("objects"));
        if (missing) Files.delete(root.resolve("old.txt"));
        success("stage-delete", "old.txt");
        assertEquals(!missing, Files.exists(root.resolve("old.txt")));
        String status = success("status");
        assertTrue(status.contains("Changes staged for commit:"), status);
        assertTrue(status.contains("deleted:      old.txt"), status);
        assertFalse(status.contains("Changes not staged for commit:"), status);
        assertEquals(!missing, status.contains("Untracked files:"), status);
        success("commit", "-m", "remove from next snapshot");
        String head = Files.readString(storage.resolve("HEAD"));
        assertFalse(new SnapshotStore(storage).load(head).files().containsKey("old.txt"));
        assertEquals(parent, new SnapshotStore(storage).load(head).parentId());
        assertArrayEquals(oldSnapshot, Files.readAllBytes(original));
        assertEquals(objects, RepositoryPreflightTest.tree(storage.resolve("objects")));
        assertEquals(!missing, Files.exists(root.resolve("old.txt")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unstagingPutPreservesWorkingStateAndOtherSelections(boolean missing) throws Exception {
        Files.writeString(root.resolve("old.txt"), "modified");
        stage("old.txt");
        Files.writeString(root.resolve("other.txt"), "other");
        stage("other.txt");
        if (missing) Files.delete(root.resolve("old.txt"));
        success("unstage", "old.txt");
        assertEquals(List.of("other.txt"), new StagingArea(storage).readAll().keySet().stream().toList());
        assertEquals(!missing, Files.exists(root.resolve("old.txt")));
        if (!missing) assertEquals("modified", Files.readString(root.resolve("old.txt")));
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
    }

    @Test void deletionOfStagedOnlyAdditionCancelsItWithoutCreatingACommit() throws Exception {
        Files.writeString(root.resolve("new.txt"), "new");
        stage("new.txt");
        success("stage-delete", "new.txt");
        assertTrue(new StagingArea(storage).readAll().isEmpty());
        assertEquals(1, run("commit", "-m", "nothing selected").code());
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        assertEquals("new", Files.readString(root.resolve("new.txt")));
    }

    @Test void unstagingDeleteRestoresInheritanceWithoutRestoringMissingFile() throws Exception {
        Files.delete(root.resolve("old.txt"));
        success("stage-delete", "old.txt");
        success("unstage", "old.txt");
        assertTrue(new StagingArea(storage).readAll().isEmpty());
        assertFalse(Files.exists(root.resolve("old.txt")));
        var status = WorkingTreeStatus.compute(root);
        assertEquals(List.of("old.txt"), status.deleted);
        assertFalse(status.isClean());
    }

    @Test void addCanReplaceAnExplicitDeletionWithCapturedContent() throws Exception {
        success("stage-delete", "old.txt");
        Files.writeString(root.resolve("old.txt"), "replacement");
        success("add", "old.txt");
        success("commit", "-m", "keep replacement");
        var snapshot = new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD")));
        assertEquals(StatusCorrectnessTest.digest("replacement"), snapshot.files().get("old.txt"));
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @Test void rootHelpListsExplicitIntentCommands() throws Exception {
        String help = success("--help");
        assertTrue(help.contains("stage-delete"), help);
        assertTrue(help.contains("unstage"), help);
    }

    @Test void addDoesNotAutomaticallySelectDeletionForMissingFilesOrDirectories() throws Exception {
        Files.delete(root.resolve("old.txt"));
        assertEquals(1, run("add", "old.txt").code());
        assertTrue(new StagingArea(storage).readOperations().isEmpty());
        success("add", ".");
        assertTrue(new StagingArea(storage).readOperations().values().stream()
                .noneMatch(operation -> operation instanceof StagedOperation.Delete));
        success("commit", "-m", "inherited missing file remains selected");
        assertTrue(new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD"))).files().containsKey("old.txt"));
        assertEquals(List.of("old.txt"), WorkingTreeStatus.compute(root).deleted);
    }

    @ParameterizedTest @ValueSource(strings = {"stage-delete", "unstage"})
    void unsafeCliPathsAndInvalidPreflightReturnErrorsWithoutChangingStorage(String command) throws Exception {
        var before = RepositoryPreflightTest.tree(storage);
        for (String path : List.of("../outside.txt", ".vrn/HEAD", ".vrnauto/state")) {
            assertEquals(1, run(command, path).code());
            assertEquals(before, RepositoryPreflightTest.tree(storage));
        }
        Files.writeString(storage.resolve("config"), "version=999");
        var invalid = RepositoryPreflightTest.tree(storage);
        assertEquals(1, run(command, "old.txt").code());
        assertEquals(invalid, RepositoryPreflightTest.tree(storage));
    }

    void stage(String name) throws Exception {
        var file = new FileHasher().hashWithBytes(root.resolve(name));
        new ObjectStore(storage).store(file.hash(), file.bytes());
        new StagingArea(storage).stage(Path.of(name), file.hash());
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
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI timeout");
            return new Run(process.exitValue(), Files.readString(output));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    record Run(int code, String output) {}
}
