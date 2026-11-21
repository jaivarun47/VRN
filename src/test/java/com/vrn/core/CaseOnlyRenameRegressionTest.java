package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class CaseOnlyRenameRegressionTest {
    @TempDir Path temporary;
    Path root;
    Path storage;
    StagingArea staging;
    String hash;
    String parent;

    @BeforeEach void prepare() throws Exception {
        root = Files.createDirectory(temporary.resolve("project"));
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        staging = new StagingArea(storage);
        hash = select("hello.txt", "original");
        var ignore = new FileHasher().hashWithBytes(root.resolve(".vrnignore"));
        new ObjectStore(storage).store(ignore.hash(), ignore.bytes());
        staging.stage(Path.of(".vrnignore"), ignore.hash());
        parent = new CommitService(storage).commit("parent").snapshotId();
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void renamedFileIsReportedAndBothExplicitSpellingsSelectCompleteTransition(boolean modified, boolean ignored) throws Exception {
        rename("hello.txt", "HELLO.TXT");
        if (modified) Files.writeString(root.resolve("HELLO.TXT"), "changed");
        if (ignored) Files.writeString(root.resolve(".vrnignore"), ".vrn/\n*.txt\n");
        String status = success("status");
        System.out.println("MT-003 before add:\n" + status);
        assertTrue(status.contains("Case-only path changes"), status);
        assertTrue(status.contains("hello.txt") && status.contains("HELLO.TXT"), status);
        assertFalse(status.contains("working directory clean"), status);
        var core = WorkingTreeStatus.compute(root);
        assertTrue(core.deleted.isEmpty());
        assertTrue(core.untracked.isEmpty());
        assertEquals(modified, core.modifiedNotStaged.contains("HELLO.TXT"));
        for (String spelling : List.of("HELLO.TXT", "hello.txt")) {
            var add = run("add", spelling);
            assertEquals(0, add.code(), add.output());
            assertTrue(add.output().contains("Files staged successfully"));
            assertEquals(Map.of("hello.txt", new StagedOperation.Delete(), "HELLO.TXT",
                    new StagedOperation.Put(StatusCorrectnessTest.digest(modified ? "changed" : "original"))), staging.readOperations());
        }
        assertTrue(WorkingTreeStatus.compute(root).caseOnlyChanges.isEmpty());
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        assertEquals(modified ? "changed" : "original", Files.readString(root.resolve("HELLO.TXT")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preexistingAlternateSpellingIsVisibleAndCannotPublishAConflictingManifest(boolean modified) throws Exception {
        rename("hello.txt", "HELLO.TXT");
        String selectedHash = modified ? store("changed") : hash;
        if (modified) Files.writeString(root.resolve("HELLO.TXT"), "changed");
        String legacySelection = MetadataCodec.encodeIndex(Map.of("HELLO.TXT", selectedHash));
        Files.writeString(storage.resolve("staging/index"), legacySelection);
        byte[] historical = Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot"));
        String status = success("status");
        assertTrue(status.contains("Case-only path changes"), status);
        assertFalse(WorkingTreeStatus.compute(root).isClean());
        assertEquals(modified, WorkingTreeStatus.compute(root).stagedModifications.contains("HELLO.TXT"));
        var commit = run("commit", "-m", "unsupported-case-transition");
        assertEquals(1, commit.code(), commit.output());
        assertTrue(commit.output().contains("case-equivalent"), commit.output());
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        assertEquals(legacySelection, Files.readString(storage.resolve("staging/index")));
        assertArrayEquals(historical, Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot")));
        assertEquals(1, snapshotCount());
        success("unstage", "hello.txt");
        assertTrue(staging.readOperations().isEmpty());
        assertTrue(success("status").contains("Case-only path changes"));
    }

    @Test void renameAfterStagingIsNotSilentlyCommittedAsTheOldSpelling() throws Exception {
        staging.stage(Path.of("hello.txt"), hash);
        byte[] before = Files.readAllBytes(storage.resolve("staging/index"));
        rename("hello.txt", "HELLO.TXT");
        var commit = run("commit", "-m", "changed-working-spelling");
        assertEquals(1, commit.code(), commit.output());
        assertTrue(commit.output().contains("Case-only"), commit.output());
        assertArrayEquals(before, Files.readAllBytes(storage.resolve("staging/index")));
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        success("unstage", "HELLO.TXT");
        assertTrue(success("status").contains("Case-only path changes"));
    }

    @Test void lookupAliasWithoutPhysicalRenameStillSelectsTheActualRecordedSpelling() throws Exception {
        success("add", "HELLO.TXT");
        assertEquals(Map.of("hello.txt", new StagedOperation.Put(hash)), staging.readOperations());
        assertTrue(WorkingTreeStatus.compute(root).isClean());
        success("commit", "-m", "content-selection-through-alias");
        var next = new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD")));
        assertTrue(next.files().containsKey("hello.txt"));
        assertFalse(next.files().containsKey("HELLO.TXT"));
    }

    @Test void repeatedRenameAndUnstageOperationsNeverLoseSpellingOrRewriteHistory() throws Exception {
        byte[] original = Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot"));
        for (int i = 0; i < 3; i++) {
            rename("hello.txt", "HELLO.TXT");
            assertTrue(success("status").contains("Case-only path changes"));
            success("add", "HELLO.TXT");
            success("unstage", "HELLO.TXT");
            success("unstage", "hello.txt");
            rename("HELLO.TXT", "hello.txt");
            assertTrue(WorkingTreeStatus.compute(root).isClean());
            success("add", "hello.txt");
            success("unstage", "HELLO.TXT");
            assertTrue(staging.readOperations().isEmpty());
        }
        assertEquals(parent, Files.readString(storage.resolve("HEAD")));
        assertArrayEquals(original, Files.readAllBytes(storage.resolve("snapshots/" + parent + ".snapshot")));
    }

    @ParameterizedTest @ValueSource(strings = {"HELLO.TXT", "hello.txt"})
    void directPutCannotBypassTheUnsupportedTransitionCheck(String spelling) throws Exception {
        rename("hello.txt", "HELLO.TXT");
        var before = RepositoryPreflightTest.tree(storage);
        var error = assertThrows(IOException.class, () -> staging.stage(Path.of(spelling), hash));
        assertTrue(error.getMessage().contains("Case-only"), error.getMessage());
        assertEquals(before, RepositoryPreflightTest.tree(storage));
    }

    @Test void stagedOnlyAdditionCanBeReselectedWithNewSpellingAfterExplicitUnstage() throws Exception {
        select("new.txt", "new");
        rename("new.txt", "NEW.TXT");
        assertTrue(success("status").contains("Case-only path changes"));
        success("add", "NEW.TXT");
        assertFalse(staging.readOperations().containsKey("new.txt"));
        success("unstage", "NEW.TXT");
        success("add", "NEW.TXT");
        assertEquals(Map.of("NEW.TXT", new StagedOperation.Put(StatusCorrectnessTest.digest("new"))), staging.readOperations());
        success("commit", "-m", "new-file-spelling");
        var next = new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD")));
        assertTrue(next.files().containsKey("NEW.TXT"));
        assertFalse(next.files().containsKey("new.txt"));
    }

    @Test void deleteAndUnstageKeepTheirMeaningWhenWorkingFileCaseChanges() throws Exception {
        rename("hello.txt", "HELLO.TXT");
        success("stage-delete", "HELLO.TXT");
        assertEquals(Map.of("hello.txt", new StagedOperation.Delete()), staging.readOperations());
        assertTrue(WorkingTreeStatus.compute(root).caseOnlyChanges.isEmpty(), "DELETE selects absence; the retained uppercase file is untracked");
        success("add", "HELLO.TXT");
        assertEquals(Map.of("hello.txt", new StagedOperation.Delete(), "HELLO.TXT", new StagedOperation.Put(hash)), staging.readOperations());
        success("unstage", "HELLO.TXT");
        assertEquals("original", Files.readString(root.resolve("HELLO.TXT")));
        success("stage-delete", "hello.txt");
        success("commit", "-m", "explicit-removal-not-rename");
        var next = new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD")));
        assertFalse(next.files().containsKey("hello.txt"));
        assertFalse(next.files().containsKey("HELLO.TXT"));
        assertTrue(Files.exists(storage.resolve("objects/" + hash)));
        assertEquals("original", Files.readString(root.resolve("HELLO.TXT")));
    }

    @Test void mixedRecordedParentSpellingsRemainUsableForContentUpdates() throws Exception {
        Files.createDirectory(root.resolve("Src"));
        Files.writeString(root.resolve("Src/A.txt"), "A");
        Files.writeString(root.resolve("Src/B.txt"), "B");
        var files = new LinkedHashMap<>(new SnapshotStore(storage).load(parent).files());
        files.put("Src/A.txt", store("A"));
        files.put("src/B.txt", store("B"));
        new SnapshotStore(storage).save(new Snapshot("mixed", parent, 1, "mixed-parent-spelling", files));
        Files.writeString(storage.resolve("HEAD"), "mixed");
        assertFalse(WorkingTreeStatus.compute(root).caseOnlyChanges.isEmpty());
        Files.writeString(root.resolve("Src/B.txt"), "B changed");
        assertEquals(1, run("add", "SRC/B.txt").code());
        success("add", "Src");
        assertEquals(Map.of("src/B.txt", new StagedOperation.Delete(), "Src/B.txt", new StagedOperation.Put(StatusCorrectnessTest.digest("B changed")),
                "Src/A.txt", new StagedOperation.Put(StatusCorrectnessTest.digest("A"))), staging.readOperations());
        success("commit", "-m", "content-only-parent-alias");
        var next = new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD")));
        assertFalse(next.files().containsKey("src/B.txt"));
        assertTrue(next.files().containsKey("Src/B.txt"));
        assertTrue(next.files().containsKey("Src/A.txt"));
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @Test void directoryCapitalizationLookupDoesNotRewriteRecordedDirectorySpelling() throws Exception {
        select("Mixed/Name.txt", "content");
        new CommitService(storage).commit("mixed-case-path");
        rename("Mixed", "MIXED");
        assertFalse(WorkingTreeStatus.compute(root).caseOnlyChanges.isEmpty());
        assertEquals(1, run("add", "MIXED/Name.txt").code());
        success("add", "MIXED");
        assertEquals(Map.of("Mixed/Name.txt", new StagedOperation.Delete(), "MIXED/Name.txt", new StagedOperation.Put(StatusCorrectnessTest.digest("content"))), staging.readOperations());
        success("commit", "-m", "content-through-directory-alias");
        var next = new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD")));
        assertFalse(next.files().containsKey("Mixed/Name.txt"));
        assertTrue(next.files().containsKey("MIXED/Name.txt"));
    }

    @Test void rejectingRenamePreservesUnrelatedStagingAndUnrelatedCommitsRemainPossible() throws Exception {
        select("pending.txt", "selected pending contents");
        byte[] before = Files.readAllBytes(storage.resolve("staging/index"));
        rename("hello.txt", "HELLO.TXT");
        assertArrayEquals(before, Files.readAllBytes(storage.resolve("staging/index")));
        success("commit", "-m", "only-the-unrelated-selected-file");
        var next = new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD")));
        assertTrue(next.files().containsKey("hello.txt"));
        assertFalse(next.files().containsKey("HELLO.TXT"));
        assertTrue(next.files().containsKey("pending.txt"));
        assertTrue(success("status").contains("Case-only path changes"));
        success("add", ".");
        success("commit", "-m", "complete selected rename");
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void genuineCaseDistinctDestinationsAreRejectedWithoutCoalescingThem(boolean ignored) throws Exception {
        Path directory = Files.createDirectory(root.resolve("Sensitive"));
        WindowsStatusIdentityTest.sensitive(directory);
        select("Sensitive/hello.txt", "lower");
        new CommitService(storage).commit("case-sensitive-fixture");
        Files.writeString(directory.resolve("HELLO.TXT"), "upper");
        assertEquals("lower", Files.readString(directory.resolve("hello.txt")));
        if (ignored) Files.writeString(root.resolve(".vrnignore"), ".vrn/\nSensitive/\n");
        var before = RepositoryPreflightTest.tree(storage);
        assertEquals(1, run("add", "Sensitive/HELLO.TXT").code());
        assertEquals(1, run("status").code());
        assertEquals(before, RepositoryPreflightTest.tree(storage));
        assertEquals("lower", Files.readString(directory.resolve("hello.txt")));
        assertEquals("upper", Files.readString(directory.resolve("HELLO.TXT")));
    }

    String store(String contents) throws Exception {
        String digest = StatusCorrectnessTest.digest(contents);
        new ObjectStore(storage).store(digest, contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return digest;
    }

    String select(String path, String contents) throws Exception {
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), contents);
        String digest = store(contents);
        staging.stage(Path.of(path), digest);
        return digest;
    }

    void rename(String from, String to) throws Exception { WindowsStatusIdentityTest.rename(root.resolve(from), root.resolve(to)); }
    long snapshotCount() throws IOException { try (var paths = Files.list(storage.resolve("snapshots"))) { return paths.count(); } }
    String success(String... arguments) throws Exception {
        var result = run(arguments);
        assertEquals(0, result.code(), result.output());
        return result.output();
    }
    Run run(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-jar", System.getProperty("vrn.executableJar")));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(temporary, "case-cli-", ".txt");
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI timeout");
            return new Run(process.exitValue(), Files.readString(output));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    record Run(int code, String output) {}
}
