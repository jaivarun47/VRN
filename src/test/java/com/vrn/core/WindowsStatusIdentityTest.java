package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsStatusIdentityTest {
    @TempDir Path root;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void caseOnlyFileSpellingChangeHasNoFalseDeletionOrUntrackedFile(boolean modified) throws Exception {
        StatusCorrectnessTest.prepare(root,"Main.java","A","-","A",".vrn/\n");
        rename(root.resolve("Main.java"),root.resolve("main.java"));
        if(modified) Files.writeString(root.resolve("main.java"),"B");
        var before=RepositoryPreflightTest.tree(root.resolve(".vrn"));
        var s=WorkingTreeStatus.compute(root);
        assertTrue(s.deleted.isEmpty()); assertTrue(s.untracked.isEmpty());
        assertEquals(modified?List.of("main.java"):List.of(),s.modifiedNotStaged);
        assertEquals(before,RepositoryPreflightTest.tree(root.resolve(".vrn")));
    }

    @Test void directoryCaseChangeAndAcceptedMixedManifestSpellingResolveToActualEntries() throws Exception {
        new RepositoryInitializer().initialize(root);
        Files.createDirectory(root.resolve("Src"));
        Files.writeString(root.resolve("Src/A.java"),"A");
        Files.writeString(root.resolve("Src/B.java"),"B");
        StatusCorrectnessTest.installHead(root,Map.of("Src/A.java",StatusCorrectnessTest.digest("A"),"src/B.java",StatusCorrectnessTest.digest("B")));
        rename(root.resolve("Src"),root.resolve("src"));
        assertTrue(WorkingTreeStatus.compute(root).isClean());
        Files.writeString(root.resolve("src/A.java"),"C");
        var status=WorkingTreeStatus.compute(root);
        assertEquals(List.of("src/A.java"),status.modifiedNotStaged);
        assertTrue(status.deleted.isEmpty()); assertTrue(status.untracked.isEmpty());
    }

    @Test void stagedAlternateSpellingIsReconciledByExistingDestinationWithoutRewritingMetadata() throws Exception {
        StatusCorrectnessTest.prepare(root,"Main.java","A","-","A",".vrn/\n");
        rename(root.resolve("Main.java"),root.resolve("main.java"));
        new StagingArea(root.resolve(".vrn")).stage(Path.of("main.java"),StatusCorrectnessTest.digest("B"));
        Files.writeString(root.resolve("main.java"),"C");
        var before=RepositoryPreflightTest.tree(root.resolve(".vrn"));
        var status=WorkingTreeStatus.compute(root);
        assertEquals(List.of("main.java"),status.stagedModifications);
        assertTrue(status.stagedNewFiles.isEmpty());
        assertEquals(List.of("main.java"),status.modifiedNotStaged);
        assertTrue(status.deleted.isEmpty()); assertTrue(status.untracked.isEmpty());
        assertEquals(before,RepositoryPreflightTest.tree(root.resolve(".vrn")));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void caseDistinctActualFilesAreRejectedExplicitlyEvenWhenOtherNameIsIgnored(boolean ignored) throws Exception {
        sensitive(root);
        StatusCorrectnessTest.prepare(root,"a.txt","A","-","A",ignored?".vrn/\nA.txt\n":".vrn/\n");
        Files.writeString(root.resolve("A.txt"),"B",StandardOpenOption.CREATE_NEW);
        Exception error=assertThrows(Exception.class,()->WorkingTreeStatus.compute(root));
        assertTrue(error.getMessage().contains("VRN support limitation"),error.getMessage());
        var result=RepositoryPreflightTest.cli(root,"status");
        assertEquals(1,result.code(),result.output());
        assertFalse(result.output().contains("working directory clean"));
    }

    @Test void caseFoldCandidateIsNotIdentityProofInsideSensitiveDirectory() throws Exception {
        sensitive(root);
        StatusCorrectnessTest.prepare(root,"Upper.txt","A","-","-",".vrn/\n");
        Files.writeString(root.resolve("upper.txt"),"A");
        var status=WorkingTreeStatus.compute(root);
        assertEquals(List.of("Upper.txt"),status.deleted);
        assertEquals(List.of("upper.txt"),status.untracked);
    }

    @ParameterizedTest @ValueSource(strings={"file","parent"})
    void ignoredTrackedJunctionIsAnErrorAndNeverReadAsDeletedOrClean(String kind) throws Exception {
        String path=kind.equals("file")?"a.txt":"build/a.txt";
        StatusCorrectnessTest.prepare(root,path,"A","-","-",".vrn/\nbuild/\na.txt\n");
        Path outside=Files.createDirectory(root.resolveSibling(root.getFileName()+"-outside"));
        Files.writeString(outside.resolve("a.txt"),"must never be hashed");
        Path link=root.resolve(kind.equals("file")?"a.txt":"build");
        WindowsMetadataBoundaryTest.junction(link,outside);
        try {
            var before=RepositoryPreflightTest.tree(outside);
            Exception error=assertThrows(IOException.class,()->WorkingTreeStatus.compute(root));
            assertTrue(error.getMessage().toLowerCase(Locale.ROOT).matches("(?s).*(reparse|junction|symbolic).*"),error.getMessage());
            var result=RepositoryPreflightTest.cli(root,"status");
            assertEquals(1,result.code());
            assertFalse(result.output().contains("working directory clean"));
            assertEquals(before,RepositoryPreflightTest.tree(outside));
        } finally {Files.delete(link); Files.delete(outside.resolve("a.txt")); Files.delete(outside);}
    }

    @Test void ignoredTrackedSymbolicFileFailsWherePrivilegesPermit() throws Exception {
        StatusCorrectnessTest.prepare(root,"a.txt","A","-","-",".vrn/\na.txt\n");
        Path target=Files.writeString(root.resolve("outside.txt"),"A");
        try {Files.createSymbolicLink(root.resolve("a.txt"),target);}
        catch(IOException|UnsupportedOperationException failure){assumeTrue(false,"Symbolic links unavailable: "+failure.getMessage());}
        try {assertThrows(IOException.class,()->WorkingTreeStatus.compute(root));}
        finally {Files.deleteIfExists(root.resolve("a.txt"));}
    }

    @Test void absentAlternateSelectionSpellingsAreExplicitlyUnresolved() throws Exception {
        StatusCorrectnessTest.prepare(root,"Main.java","A","-","-",".vrn/\n");
        new StagingArea(root.resolve(".vrn")).stage(Path.of("main.java"),StatusCorrectnessTest.digest("B"));
        var before=RepositoryPreflightTest.tree(root.resolve(".vrn"));
        IOException error=assertThrows(IOException.class,()->WorkingTreeStatus.compute(root));
        assertTrue(error.getMessage().contains("VRN support limitation"),error.getMessage());
        assertEquals(before,RepositoryPreflightTest.tree(root.resolve(".vrn")));
    }

    @Test void hardLinkNamesRemainIndependentStatusDestinations() throws Exception {
        new RepositoryInitializer().initialize(root);
        Files.writeString(root.resolve("first.txt"),"A");
        try {Files.createLink(root.resolve("second.txt"),root.resolve("first.txt"));}
        catch(IOException|UnsupportedOperationException failure){assumeTrue(false,"Hard links unavailable: "+failure.getMessage());}
        StatusCorrectnessTest.installHead(root,Map.of("first.txt",StatusCorrectnessTest.digest("A"),"second.txt",StatusCorrectnessTest.digest("A")));
        assertTrue(WorkingTreeStatus.compute(root).isClean());
        Files.writeString(root.resolve("first.txt"),"B");
        assertEquals(List.of("first.txt","second.txt"),WorkingTreeStatus.compute(root).modifiedNotStaged);
    }

    static void sensitive(Path directory) throws Exception {
        Process process=new ProcessBuilder("fsutil.exe","file","setCaseSensitiveInfo",directory.toString(),"enable").redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10,TimeUnit.SECONDS));
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assumeTrue(process.exitValue()==0,"Disposable case-sensitive directory unavailable: "+output);
        } finally {if(process.isAlive()) process.destroyForcibly();}
    }
    static void rename(Path from,Path to) throws Exception {
        Path temporary=from.resolveSibling("case-change-temporary");
        Files.move(from,temporary); Files.move(temporary,to);
    }
}
