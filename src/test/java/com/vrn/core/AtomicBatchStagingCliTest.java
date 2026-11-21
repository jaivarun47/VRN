package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real executable, real object corruption, isolated repositories only. */
class AtomicBatchStagingCliTest {
    @TempDir Path temporary;
    Path root;
    Path storage;
    Path index;
    StagingArea staging;

    @BeforeEach void prepare() throws Exception {
        root = Files.createDirectory(temporary.resolve("project"));
        success("init");
        storage = root.resolve(".vrn");
        index = storage.resolve("staging/index");
        staging = new StagingArea(storage);
        write("batch/0.txt", "old zero");
        write("batch/1.txt", "old one");
        write("obsolete.txt", "old obsolete");
        success("add", ".");
        success("commit", "-m", "parent");
    }

    @ParameterizedTest @CsvSource({
            "none,0", "none,1", "none,2", "none,3",
            "v1,0", "v1,1", "v1,2", "v1,3",
            "v2,0", "v2,1", "v2,2", "v2,3"})
    void failureAtAnyObjectLeavesEntireIndexUnchanged(String version, int failureAt) throws Exception {
        if (!version.equals("none")) {
            success("add", "batch/0.txt"); // This PUT would otherwise be replaced.
            write("pending-a.txt", "pending a");
            write("pending-b.txt", "pending b");
            success("add", "pending-a.txt");
            success("add", "pending-b.txt");
            if (version.equals("v2")) {
                success("stage-delete", "batch/1.txt"); // This DELETE would otherwise be replaced.
                success("stage-delete", "obsolete.txt"); // Unrelated DELETE must survive.
            }
            assertTrue(Files.readString(index).startsWith("VRN-STAGING\t" + (version.equals("v2") ? "2" : "1")));
        }
        byte[] baseline = bytesOrAbsent();
        var intent = staging.readOperations();
        String head = Files.readString(storage.resolve("HEAD"));
        var history = RepositoryPreflightTest.tree(storage.resolve("snapshots"));
        for (int i = 0; i < 4; i++) write("batch/" + i + ".txt", "new batch contents " + i);
        var selected = new FileScanner().scanForAdd(root, Path.of("batch"));
        assertEquals(4, selected.size());
        ScannedFile rejected = selected.get(failureAt);
        Path corrupt = storage.resolve("objects").resolve(rejected.hash());
        Files.writeString(corrupt, "deliberately corrupt existing object");

        Run result = run("add", "batch");
        assertEquals(1, result.code(), result.output());
        assertFalse(result.output().contains("Files staged successfully"), result.output());
        assertArrayEquals(baseline, bytesOrAbsent(), "A failed batch must preserve the entire serialized index");
        assertEquals(intent, staging.readOperations());
        assertEquals(head, Files.readString(storage.resolve("HEAD")));
        assertEquals(history, RepositoryPreflightTest.tree(storage.resolve("snapshots")));
        for (int i = 0; i < failureAt; i++) new ObjectStore(storage).verify(selected.get(i).hash());

        // Repair only our deliberately damaged test fixture; a subsequent ordinary add must work.
        Files.write(corrupt, rejected.bytes());
        success("add", "batch");
        var after = staging.readOperations();
        for (var file : selected) assertEquals(new StagedOperation.Put(file.hash()), after.get(file.path().toString().replace('\\', '/')));
        if (version.equals("v2")) assertInstanceOf(StagedOperation.Delete.class, after.get("obsolete.txt"));
        success("status");
        success("commit", "-m", "complete batch");
        var snapshot = new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD")));
        for (var file : selected) assertEquals(file.hash(), snapshot.files().get(file.path().toString().replace('\\', '/')));
        if (version.equals("v2")) assertFalse(snapshot.files().containsKey("obsolete.txt"));
    }

    @Test void rootBatchPreservesIgnoreMetadataAndSpecialNamesAndCommits() throws Exception {
        write(".vrnignore", "temp/\nobsolete.txt\n");
        write("temp/ignored.txt", "not selected");
        write("obsolete.txt", "ignored tracked update");
        write("temp/staged.txt", "staged-only initial");
        // Create a staged-only ignored path through the core API, then update it through add.
        var captured = new FileHasher().hashWithBytes(root.resolve("temp/staged.txt"));
        new ObjectStore(storage).store(captured.hash(), captured.bytes());
        staging.stage(Path.of("temp/staged.txt"), captured.hash());
        write("temp/staged.txt", "ignored staged-only update");
        write("resources/nested/config=production.txt", "production=true");
        write("resources/nested/#notes.txt", "notes");
        write("resources/日本語.txt", "unicode");
        write(".vrnauto/private.txt", "reserved");
        var automatic = RepositoryPreflightTest.tree(root.resolve(".vrnauto"));
        assertTrue(success("add", ".").contains("Files staged successfully"));
        var operations = staging.readOperations();
        assertFalse(operations.containsKey("temp/ignored.txt"));
        assertTrue(operations.containsKey("obsolete.txt"));
        assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest("ignored staged-only update")), operations.get("temp/staged.txt"));
        assertTrue(operations.containsKey("resources/nested/config=production.txt"));
        assertTrue(operations.containsKey("resources/nested/#notes.txt"));
        assertTrue(operations.containsKey("resources/日本語.txt"));
        assertTrue(operations.keySet().stream().noneMatch(p -> p.startsWith(".vrn/") || p.startsWith(".vrnauto/")));
        byte[] before = bytesOrAbsent();
        assertEquals(1, run("add", "temp/ignored.txt").code());
        assertArrayEquals(before, bytesOrAbsent());
        Files.createDirectory(root.resolve("empty"));
        assertTrue(success("add", "empty").contains("No eligible files"));
        assertArrayEquals(before, bytesOrAbsent());
        success("status");
        success("commit", "-m", "Batch staging test");
        assertTrue(WorkingTreeStatus.compute(root).isClean());
        assertEquals(automatic, RepositoryPreflightTest.tree(root.resolve(".vrnauto")));
    }

    byte[] bytesOrAbsent() throws Exception { return Files.exists(index) ? Files.readAllBytes(index) : null; }
    void write(String relative, String contents) throws Exception {
        Files.createDirectories(root.resolve(relative).getParent());
        Files.writeString(root.resolve(relative), contents);
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
        Path log = Files.createTempFile(temporary, "cli-", ".txt");
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI timeout");
            return new Run(process.exitValue(), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    record Run(int code, String output) {}
}
