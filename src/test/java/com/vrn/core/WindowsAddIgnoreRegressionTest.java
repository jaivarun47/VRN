package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsAddIgnoreRegressionTest {
    @TempDir Path temporary;
    AddIgnoreRegressionTest fixture;

    @BeforeEach void prepare() throws Exception {
        fixture = new AddIgnoreRegressionTest();
        fixture.temporary = temporary;
        fixture.initialize();
    }

    @Test void actualTrackedIdentityAllowsExplicitCaseAliasWithoutChangingStoredSelection() throws Exception {
        assumeTrue(Files.exists(fixture.root.resolve("NOTES.TXT")), "Case-insensitive lookup required");
        Files.writeString(fixture.root.resolve("notes.txt"), "current bytes");
        fixture.rules("notes.txt");
        fixture.success("add", "NOTES.TXT");
        assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest("current bytes")), fixture.staging.readOperations().get("notes.txt"));
        assertTrue(WorkingTreeStatus.compute(fixture.root).stagedModifications.contains("notes.txt"));
    }

    @Test void untrackedCaseAliasCannotBypassActualNameIgnoreRuleOrSelectTrackedSibling() throws Exception {
        Files.writeString(fixture.root.resolve("temp/cache.txt"), "cache");
        fixture.rules("temp/");
        var result = fixture.run("add", "TEMP/CACHE.TXT");
        assertEquals(1, result.code(), result.output());
        assertTrue(result.output().contains("Ignored untracked file"));
        assertTrue(fixture.staging.readOperations().isEmpty());
    }

    @Test void requestedCaseIgnoreMatchStillHasTruthfulDiagnostic() throws Exception {
        Files.writeString(fixture.root.resolve("Debug.LOG"), "cache");
        fixture.rules("*.log");
        var result = fixture.run("add", "debug.log");
        assertEquals(1, result.code(), result.output());
        assertTrue(result.output().contains("Ignored untracked file"));
        assertFalse(result.output().contains("directory is empty"));
        assertTrue(fixture.staging.readOperations().isEmpty());
    }

    @Test void hardLinkNameIsNotConsideredTrackedBecauseItSharesAFileKey() throws Exception {
        Files.createLink(fixture.root.resolve("notes-copy.txt"), fixture.root.resolve("notes.txt"));
        fixture.rules("notes-copy.txt");
        var result = fixture.run("add", "notes-copy.txt");
        assertEquals(1, result.code(), result.output());
        assertTrue(fixture.staging.readOperations().isEmpty());
        assertEquals("original notes", Files.readString(fixture.root.resolve("notes.txt")));
    }

    @Test void ignoredTrackedCaseAmbiguityFailsWithoutPublishingAnIndex() throws Exception {
        Path sensitive = Files.createDirectory(fixture.root.resolve("Sensitive"));
        WindowsStatusIdentityTest.sensitive(sensitive);
        fixture.select("Sensitive/Main.txt", "original");
        new CommitService(fixture.storage).commit("case-sensitive fixture");
        Files.writeString(sensitive.resolve("main.txt"), "different");
        assertEquals("original", Files.readString(sensitive.resolve("Main.txt")));
        fixture.rules("Sensitive/");
        var before = RepositoryPreflightTest.tree(fixture.storage);
        var result = fixture.run("add", "Sensitive/Main.txt");
        assertEquals(1, result.code(), result.output());
        assertTrue(result.output().contains("ambiguous"), result.output());
        assertEquals(before, RepositoryPreflightTest.tree(fixture.storage));
    }

    @Test void ignoredTrackedJunctionIsNotFollowedOrSilentlySkippedByAdd() throws Exception {
        Path link = fixture.root.resolve("temp");
        Files.delete(link.resolve("tracked.txt"));
        Files.delete(link);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("tracked.txt"), "outside contents");
        WindowsMetadataBoundaryTest.junction(link, outside);
        try {
            fixture.rules("temp/");
            var before = RepositoryPreflightTest.tree(fixture.storage);
            var result = fixture.run("add", ".");
            assertEquals(1, result.code(), result.output());
            assertTrue(result.output().contains("reparse"), result.output());
            assertEquals(before, RepositoryPreflightTest.tree(fixture.storage));
            assertEquals("outside contents", Files.readString(outside.resolve("tracked.txt")));
        } finally { Files.delete(link); }
    }

    @Test void ignoredUntrackedJunctionRemainsExcluded() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("private.txt"), "private");
        Path link = fixture.root.resolve("cache");
        WindowsMetadataBoundaryTest.junction(link, outside);
        try {
            fixture.rules("cache/");
            fixture.success("add", ".");
            assertFalse(fixture.staging.readOperations().keySet().stream().anyMatch(path -> path.startsWith("cache/")));
            assertEquals("private", Files.readString(outside.resolve("private.txt")));
        } finally { Files.delete(link); }
    }

    @Test void existingProjectRootAliasRemainsSupportedWithIgnoredTrackedContents() throws Exception {
        Path realRoot = fixture.root;
        Path alias = temporary.resolve("project-alias");
        WindowsMetadataBoundaryTest.junction(alias, realRoot);
        try {
            Files.writeString(realRoot.resolve("notes.txt"), "through alias");
            fixture.rules("notes.txt");
            fixture.root = alias;
            fixture.success("add", "notes.txt");
            assertEquals(new StagedOperation.Put(StatusCorrectnessTest.digest("through alias")), fixture.staging.readOperations().get("notes.txt"));
        } finally { fixture.root = realRoot; Files.delete(alias); }
    }
}
