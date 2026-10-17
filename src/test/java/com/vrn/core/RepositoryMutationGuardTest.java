package com.vrn.core;

import com.vrn.VrnApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryMutationGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void competingStagingAndCommitOperations_failFastWhileTheStorageIsLocked() throws Exception {
        Path storage = initialize(tempDir.resolve("manual-storage"));
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            assertBusy(() -> new StagingArea(storage).stage(Path.of("file.txt"), "hash1"));
            assertBusy(() -> new StagingArea(storage).clear());
            assertBusy(() -> new CommitService(storage).commit("competing commit"));
        }

        new StagingArea(storage).stage(Path.of("file.txt"), "hash1");
        assertEquals(Map.of("file.txt", "hash1"), new StagingArea(storage).readAll());
    }

    @Test
    void separateStorageDirectories_haveIndependentLocks() throws Exception {
        Path manual = initialize(tempDir.resolve("manual-storage"));
        Path other = initialize(tempDir.resolve("other-storage"));
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(manual)) {
            new StagingArea(other).stage(Path.of("file.txt"), "hash1");
            assertEquals(Map.of("file.txt", "hash1"), new StagingArea(other).readAll());
        }
    }

    @Test
    void lockRejectsASecondJvm_andCanBeAcquiredAfterRelease() throws Exception {
        Path storage = initialize(tempDir.resolve("manual-storage"));
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            Run result = run(tempDir, LockProbe.class, storage.toString());
            assertEquals(0, result.exitCode());
            assertEquals("busy", result.output().strip());
        }
        Run result = run(tempDir, LockProbe.class, storage.toString());
        assertEquals(0, result.exitCode());
        assertEquals("acquired", result.output().strip());
    }

    @Test
    void actualCli_canInitializeStageCommitAndReportARejectedConcurrentCommit() throws Exception {
        assertEquals(0, run(tempDir, VrnApplication.class, "init").exitCode());
        Path storage = tempDir.resolve(".vrn");
        assertEquals("NONE", Files.readString(storage.resolve("HEAD")));
        Files.writeString(tempDir.resolve("file.txt"), "CLI content");
        assertEquals(0, run(tempDir, VrnApplication.class, "add", "file.txt").exitCode());

        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            Run rejected = run(tempDir, VrnApplication.class, "commit", "-m", "busy");
            assertEquals(1, rejected.exitCode());
            assertTrue(rejected.output().contains("Repository is busy"));
            assertFalse(rejected.output().contains("Committed snapshot"));
            assertEquals("NONE", Files.readString(storage.resolve("HEAD")));
        }

        Run committed = run(tempDir, VrnApplication.class, "commit", "-m", "CLI commit");
        assertEquals(0, committed.exitCode(), committed.output());
        String active = Files.readString(storage.resolve("HEAD"));
        assertTrue(committed.output().contains("Committed snapshot " + active));
        Snapshot snapshot = new SnapshotStore(storage).load(active);
        assertEquals("CLI commit", snapshot.message());
        String hash = snapshot.files().get("file.txt");
        assertEquals("CLI content", Files.readString(storage.resolve("objects/" + hash)));
        assertTrue(new StagingArea(storage).readAll().isEmpty());
    }

    private Path initialize(Path storage) throws IOException {
        Files.createDirectories(storage.resolve("staging"));
        Files.createDirectories(storage.resolve("snapshots"));
        Files.writeString(storage.resolve("HEAD"), "NONE");
        return storage;
    }

    private void assertBusy(org.junit.jupiter.api.function.Executable action) {
        IOException error = assertThrows(IOException.class, action);
        assertTrue(error.getMessage().contains("Repository is busy"));
    }

    private Run run(Path directory, Class<?> mainClass, String... args) throws Exception {
        String classpath = location(RepositoryMutationGuardTest.class) + File.pathSeparator
                + location(VrnApplication.class) + File.pathSeparator + location(CommandLine.class);
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        command.addAll(Arrays.asList("-cp", classpath, mainClass.getName()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Child JVM did not complete");
            return new Run(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private record Run(int exitCode, String output) {
    }

    public static class LockProbe {
        public static void main(String[] args) throws Exception {
            try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(Path.of(args[0]))) {
                System.out.println("acquired");
            } catch (IOException failure) {
                if (!failure.getMessage().contains("Repository is busy")) {
                    throw failure;
                }
                System.out.println("busy");
            }
        }
    }
}
