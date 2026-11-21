package com.vrn.core;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The packaged executable consumes complete transitions, including larger-than-heap replacements. */
class PathTransitionCliTest {
    @TempDir Path temporary;
    Path root;
    PathTransitionTest fixture;

    void prepare(String kind) throws Exception {
        root = Files.createDirectory(temporary.resolve("project"));
        fixture = new PathTransitionTest();
        fixture.root = root;
        fixture.prepare(kind);
    }

    @ParameterizedTest @ValueSource(strings = {"file-case", "dir-case", "file-dir", "dir-file"})
    void packagedCommandsReportStageAndCommitCompleteTransition(String kind) throws Exception {
        prepare(kind);
        var before = success("status");
        assertFalse(before.contains("working directory clean"));
        assertTrue(success("add", fixture.selection(kind)).contains("Files staged successfully"));
        var operations = new StagingArea(fixture.storage).readOperations();
        for (String old : fixture.oldPaths(kind)) assertInstanceOf(StagedOperation.Delete.class, operations.get(old));
        for (String actual : fixture.newPaths(kind)) assertInstanceOf(StagedOperation.Put.class, operations.get(actual));
        String stagedStatus = success("status");
        assertTrue(stagedStatus.contains("Changes staged for commit"));
        success("commit", "-m", "packaged transition " + kind);
        var next = new SnapshotStore(fixture.storage).load(Files.readString(fixture.storage.resolve("HEAD")));
        for (String old : fixture.oldPaths(kind)) assertFalse(next.files().containsKey(old));
        for (String actual : fixture.newPaths(kind)) assertTrue(next.files().containsKey(actual));
        assertEquals(fixture.parent, next.parentId());
        assertArrayEquals(fixture.originalSnapshot, Files.readAllBytes(fixture.storage.resolve("snapshots/" + fixture.parent + ".snapshot")));
        assertTrue(success("status").contains("working directory clean"));
        System.out.println("CLI transition " + kind + "\nbefore:\n" + before + "staged:\n" + stagedStatus + "snapshot=" + next.files().keySet());
    }

    @ParameterizedTest @ValueSource(strings = {"file-case", "dir-case", "file-dir", "dir-file"})
    void realCorruptObjectFailurePreservesExactIndexAndCanRetry(String kind) throws Exception {
        prepare(kind);
        for (String path : fixture.newPaths(kind)) fixture.write(path, "new contents " + path);
        var known = new LinkedHashSet<>(fixture.original.keySet());
        known.addAll(new StagingArea(fixture.storage).readOperations().keySet());
        List<SelectedFile> selected;
        try (var ignored = RepositoryMutationGuard.acquire(fixture.storage)) {
            selected = new FileScanner().selectForAddLocked(root, Path.of(fixture.selection(kind)), known);
        }
        var rejected = selected.getLast();
        String hash = StreamingCaptureCliTest.independentHash(root.resolve(rejected.source()));
        Path object = fixture.storage.resolve("objects/" + hash);
        Files.writeString(object, "deliberately corrupt fixture");
        byte[] baseline = Files.readAllBytes(fixture.index);
        var failure = run("add", fixture.selection(kind));
        assertEquals(1, failure.code(), failure.output());
        assertTrue(failure.output().contains("no partial staging-index update was published"));
        assertArrayEquals(baseline, Files.readAllBytes(fixture.index));
        assertEquals(fixture.parent, Files.readString(fixture.storage.resolve("HEAD")));
        assertArrayEquals(fixture.originalSnapshot, Files.readAllBytes(fixture.storage.resolve("snapshots/" + fixture.parent + ".snapshot")));
        // Repair only this deliberately damaged fixture, then exercise an ordinary CLI retry.
        Files.copy(root.resolve(rejected.source()), object, StandardCopyOption.REPLACE_EXISTING);
        success("add", fixture.selection(kind));
        success("commit", "-m", "retry complete transition");
        assertTrue(success("status").contains("working directory clean"));
    }

    @ParameterizedTest @ValueSource(strings = {"file-dir", "dir-file"})
    void structuralReplacementLargerThanHeapKeepsStreaming(String kind) throws Exception {
        prepare(kind);
        String actual = fixture.newPaths(kind).getFirst();
        StreamingCaptureCliTest.generate(root.resolve(actual), 128L * 1024 * 1024, 501);
        String hash = StreamingCaptureCliTest.independentHash(root.resolve(actual));
        success("status");
        success("add", fixture.selection(kind));
        assertEquals(new StagedOperation.Put(hash), new StagingArea(fixture.storage).readOperations().get(actual));
        assertEquals(hash, StreamingCaptureCliTest.independentHash(fixture.storage.resolve("objects/" + hash)));
        success("status");
        success("commit", "-m", "128 MiB structural replacement under 64 MiB heap");
        var next = new SnapshotStore(fixture.storage).load(Files.readString(fixture.storage.resolve("HEAD")));
        assertEquals(hash, next.files().get(actual));
        for (String old : fixture.oldPaths(kind)) assertFalse(next.files().containsKey(old));
        assertArrayEquals(fixture.originalSnapshot, Files.readAllBytes(fixture.storage.resolve("snapshots/" + fixture.parent + ".snapshot")));
        assertTrue(success("status").contains("working directory clean"));
        System.out.println("Constrained transition " + kind + ": source=134217728 bytes; heap=67108864 bytes; correct snapshot digest=" + hash);
    }

    String success(String... args) throws Exception {
        var result = run(args);
        assertEquals(0, result.code(), result.output());
        return result.output();
    }
    Run run(String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Xmx64m", "-jar", System.getProperty("vrn.executableJar")));
        command.addAll(List.of(args));
        Path log = Files.createTempFile(temporary, "transition-cli-", ".log");
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Transition CLI timeout");
            return new Run(process.exitValue(), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    record Run(int code, String output) {}
}
