package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsStagingIntentTest {
    @TempDir Path temporary;
    Path root;
    Path storage;
    StagingArea staging;
    String hash;

    @BeforeEach void prepare() throws Exception {
        root = Files.createDirectory(temporary.resolve("project"));
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        staging = new StagingArea(storage);
        Files.createDirectory(root.resolve("Src"));
        Files.writeString(root.resolve("Src/Main.java"), "original");
        var file = new FileHasher().hashWithBytes(root.resolve("Src/Main.java"));
        hash = file.hash();
        new ObjectStore(storage).store(hash, file.bytes());
        staging.stage(Path.of("Src/Main.java"), hash);
        new CommitService(storage).commit("parent");
    }

    @Test void existingCaseAliasesSelectStoredDestinationAndPutCanReplaceDeleteWithoutRenamingHistory() throws Exception {
        WindowsStatusIdentityTest.rename(root.resolve("Src/Main.java"), root.resolve("Src/main.java"));
        WindowsStatusIdentityTest.rename(root.resolve("Src"), root.resolve("src"));
        staging.stageDelete(Path.of("src/main.java"));
        assertEquals(Map.of("Src/Main.java", new StagedOperation.Delete()), staging.readOperations());
        assertEquals(java.util.List.of("src/main.java"), WorkingTreeStatus.compute(root).untracked.stream()
                .filter(path -> !path.equals(".vrnignore")).toList());
        assertTrue(staging.unstage(Path.of("SRC/MAIN.JAVA")));
        staging.stageDelete(Path.of("src/main.java"));
        staging.stage(Path.of("src/main.java"), hash);
        assertEquals(Map.of("Src/Main.java", new StagedOperation.Put(hash)), staging.readOperations());
        String next = new CommitService(storage).commit("restore content selection").snapshotId();
        assertEquals(Map.of("Src/Main.java", hash), new SnapshotStore(storage).load(next).files());
        assertTrue(Files.exists(root.resolve("src/main.java")));
    }

    @Test void missingSelectionsUseExactStoredSpellingInsteadOfGuessingCaseIdentity() throws Exception {
        Files.delete(root.resolve("Src/Main.java"));
        assertThrows(IOException.class, () -> staging.stageDelete(Path.of("src/main.java")));
        staging.stageDelete(Path.of("Src/Main.java"));
        assertThrows(IOException.class, () -> staging.unstage(Path.of("src/main.java")));
        assertTrue(staging.unstage(Path.of("Src/Main.java")));
        assertFalse(Files.exists(root.resolve("Src/Main.java")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void caseDistinctActualEntriesAreRejectedWithoutSelectingAnAlias(boolean unstage) throws Exception {
        Path sensitive = Files.createDirectory(root.resolve("Sensitive"));
        WindowsStatusIdentityTest.sensitive(sensitive);
        Files.writeString(sensitive.resolve("Main.java"), "original");
        staging.stage(Path.of("Sensitive/Main.java"), hash);
        new CommitService(storage).commit("case-sensitive selected path");
        staging.stage(Path.of("Sensitive/Main.java"), hash);
        Files.writeString(sensitive.resolve("main.java"), "distinct");
        assertEquals("original", Files.readString(sensitive.resolve("Main.java")));
        assertEquals("distinct", Files.readString(sensitive.resolve("main.java")));
        String before = Files.readString(storage.resolve("staging/index"));
        assertThrows(IOException.class, () -> {
            if (unstage) staging.unstage(Path.of("Sensitive/Main.java"));
            else staging.stageDelete(Path.of("Sensitive/Main.java"));
        });
        assertEquals(before, Files.readString(storage.resolve("staging/index")));
        assertEquals("original", Files.readString(sensitive.resolve("Main.java")));
        assertEquals("distinct", Files.readString(sensitive.resolve("main.java")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void trackedJunctionsAreNotTreatedAsAbsentOrFollowedOutside(boolean unstage) throws Exception {
        staging.stage(Path.of("Src/Main.java"), hash);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("private.txt"), "must remain untouched");
        Files.delete(root.resolve("Src/Main.java"));
        Path link = root.resolve("Src/Main.java");
        WindowsMetadataBoundaryTest.junction(link, outside);
        try {
            var before = RepositoryPreflightTest.tree(storage);
            assertThrows(IOException.class, () -> {
                if (unstage) staging.unstage(Path.of("Src/Main.java"));
                else staging.stageDelete(Path.of("Src/Main.java"));
            });
            assertEquals(before, RepositoryPreflightTest.tree(storage));
            assertEquals("must remain untouched", Files.readString(outside.resolve("private.txt")));
        } finally { Files.delete(link); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void metadataJunctionCannotRedirectEitherMutation(boolean unstage) throws Exception {
        staging.stage(Path.of("Src/Main.java"), hash);
        Path real = storage.resolve("staging");
        Path saved = storage.resolve("saved-staging");
        Files.move(real, saved);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("index"), "private");
        WindowsMetadataBoundaryTest.junction(real, outside);
        try {
            assertThrows(IOException.class, () -> {
                if (unstage) staging.unstage(Path.of("Src/Main.java"));
                else staging.stageDelete(Path.of("Src/Main.java"));
            });
            assertEquals("private", Files.readString(outside.resolve("index")));
        } finally { Files.delete(real); Files.move(saved, real); }
    }

    @Test void hardLinkNamesRemainIndependentSnapshotDestinations() throws Exception {
        Files.createLink(root.resolve("other.java"), root.resolve("Src/Main.java"));
        staging.stage(Path.of("other.java"), hash);
        new CommitService(storage).commit("two names");
        staging.stageDelete(Path.of("Src/Main.java"));
        String next = new CommitService(storage).commit("one selected deletion").snapshotId();
        assertEquals(Map.of("other.java", hash), new SnapshotStore(storage).load(next).files());
        assertEquals("original", Files.readString(root.resolve("other.java")));
        assertEquals("original", Files.readString(root.resolve("Src/Main.java")));
    }

    @Test void symbolicFileSelectionIsRejectedWherePrivilegesPermit() throws Exception {
        staging.stage(Path.of("Src/Main.java"), hash);
        Files.delete(root.resolve("Src/Main.java"));
        Path target = Files.writeString(temporary.resolve("target.txt"), "outside");
        Path link = root.resolve("Src/Main.java");
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException failure) { assumeTrue(false, "Symbolic link privilege unavailable: " + failure.getMessage()); }
        try {
            assertThrows(IOException.class, () -> staging.stageDelete(Path.of("Src/Main.java")));
            assertThrows(IOException.class, () -> staging.unstage(Path.of("Src/Main.java")));
            assertEquals("outside", Files.readString(target));
        } finally { Files.deleteIfExists(link); }
    }
}
