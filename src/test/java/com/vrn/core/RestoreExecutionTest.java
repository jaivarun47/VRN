package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RestoreExecutionTest {
    @TempDir Path root;
    SnapshotInspectionTest fixture;
    Path storage;
    @BeforeEach void initialize() throws Exception {
        fixture=new SnapshotInspectionTest(); fixture.root=root; fixture.initialize(); storage=fixture.storage;
    }
    String object(String text) throws Exception { return fixture.object(text); }
    Path write(String path,String text) throws Exception { return fixture.write(path,text); }
    void history(Map<String,String> old,Map<String,String> current) throws Exception { fixture.history(old,current); }
    void replacement() throws Exception {
        String old=object("old"),head=object("head");
        history(Map.of("Alpha",old,"Beta",old,"Gamma",old),Map.of("Alpha",head,"Beta",head,"Gamma",head));
        for(String path:List.of("Alpha","Beta","Gamma")) write(path,"head");
    }
    void noPending() { assertFalse(Files.exists(storage.resolve("restore.pending"))); assertFalse(Files.exists(storage.resolve("restore-work"))); }

    @Test void wholeTreeRestorePerformsAllActionsAndRepeatedRestoreIsHarmless() throws Exception {
        String old=object("old"),head=object("head"),same=object("same");
        history(Map.of("replace",old,"keep",same,"historical",old),Map.of("replace",head,"keep",same,"remove",head));
        write("replace","head"); write("keep","same"); write("remove","head"); write("untracked","preserve"); write("ignored","preserve"); write(".vrnignore","ignored\n");
        write(".vrnauto/sentinel","protected");
        var metadata=RepositoryPreflightTest.tree(storage);
        var result=new RestoreService(root).restore("old"); assertFalse(result.alreadyMatches());
        assertEquals("old",Files.readString(root.resolve("replace"))); assertEquals("old",Files.readString(root.resolve("historical")));
        assertEquals("same",Files.readString(root.resolve("keep"))); assertFalse(Files.exists(root.resolve("remove")));
        assertEquals("preserve",Files.readString(root.resolve("untracked"))); assertEquals("preserve",Files.readString(root.resolve("ignored")));
        assertEquals(metadata,RepositoryPreflightTest.tree(storage)); noPending();
        var before=RepositoryPreflightTest.tree(root); assertTrue(new RestoreService(root).restore("old").alreadyMatches()); assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @Test void emptyHistoricalSnapshotAndMissingHeadFileAreSupported() throws Exception {
        history(Map.of(),Map.of("remove",object("head"))); write("remove","head");
        new RestoreService(root).restore("old"); assertFalse(Files.exists(root.resolve("remove"))); noPending();
        new RestoreService(root).restore("HEAD"); assertEquals("head",Files.readString(root.resolve("remove"))); noPending();
    }

    @Test void restoredContentsCanBeCommittedWithThePreviousHeadAsParent() throws Exception {
        replacement(); new RestoreService(root).restore("old");
        assertFalse(WorkingTreeStatus.compute(root).isClean());
        new AddService(root).add(Path.of(".")); String next=new CommitService(storage).commit("restored contents").snapshotId();
        var snapshot=new SnapshotStore(storage).load(next); assertEquals("current",snapshot.parentId());
        assertEquals(object("old"),snapshot.files().get("Alpha"));
    }

    @ParameterizedTest @ValueSource(strings={"file-case","directory-case","nested-case","file-dir","dir-file","nested-file-dir","nested-dir-file"})
    void structuralTransitionsRestoreExactSpellingAndContents(String kind) throws Exception {
        String oldPath=switch(kind) {
            case "file-case" -> "HELLO.txt"; case "directory-case" -> "src/Main.java"; case "nested-case" -> "src/NEST/Main.java";
            case "file-dir" -> "config/settings.json"; case "dir-file" -> "assets"; case "nested-file-dir" -> "tree/node/data/file";
            default -> "tree/node";
        };
        String headPath=switch(kind) {
            case "file-case" -> "hello.txt"; case "directory-case" -> "SRC/Main.java"; case "nested-case" -> "SRC/nest/Main.java";
            case "file-dir" -> "config"; case "dir-file" -> "assets/icons/logo"; case "nested-file-dir" -> "TREE/node";
            default -> "TREE/node/deep/file";
        };
        history(Map.of(oldPath,object("old")),Map.of(headPath,object("head"))); write(headPath,"head");
        var metadata=RepositoryPreflightTest.tree(storage);
        new RestoreService(root).restore("old");
        Path actual=new WorkingTreePaths(root).trackedFile(oldPath); assertEquals(oldPath,actual.toString().replace('\\','/'));
        assertEquals("old",Files.readString(root.resolve(actual))); assertEquals(metadata,RepositoryPreflightTest.tree(storage)); noPending();
    }

    @ParameterizedTest @ValueSource(strings={"日本語/計算.java","space name.txt","config=production.txt","#notes.txt"})
    void unicodeAndSerializationSensitivePathsRestoreLosslessly(String path) throws Exception {
        history(Map.of(path,object("old")),Map.of(path,object("head"))); write(path,"head");
        new RestoreService(root).restore("old"); assertEquals("old",Files.readString(root.resolve(path))); noPending();
    }

    @ParameterizedTest @ValueSource(strings={"modified","untracked","ignored","staged-put","staged-delete","staged-only","unknown","missing-object","corrupt-object","read-only"})
    void unsafeExecutionIsRefusedWithoutWorkingChanges(String conflict) throws Exception {
        String old=object("old"),head=object("head");
        boolean untracked=Set.of("untracked","ignored","staged-only").contains(conflict);
        history(Map.of("file",old),untracked?Map.of():Map.of("file",head)); write("file",conflict.equals("modified")?"local":"head");
        String selector="old";
        switch(conflict) {
            case "ignored" -> write(".vrnignore","file\n");
            case "staged-put","staged-only" -> new AddService(root).add(Path.of("file"));
            case "staged-delete" -> new StagingArea(storage).stageDelete(Path.of("file"));
            case "unknown" -> selector="unknown";
            case "missing-object" -> Files.delete(storage.resolve("objects/"+old));
            case "corrupt-object" -> Files.writeString(storage.resolve("objects/"+old),"bad");
            case "read-only" -> Files.setAttribute(root.resolve("file"),"dos:readonly",true,LinkOption.NOFOLLOW_LINKS);
        }
        var before=RepositoryPreflightTest.tree(root); String selected=selector;
        try { assertThrows(Exception.class,()->new RestoreService(root).restore(selected)); assertEquals(before,RepositoryPreflightTest.tree(root)); noPending(); }
        finally { if(conflict.equals("read-only")) Files.setAttribute(root.resolve("file"),"dos:readonly",false,LinkOption.NOFOLLOW_LINKS); }
    }

    @ParameterizedTest @ValueSource(strings={"untracked","ignored","empty-directory","junction"})
    void structuralObstructionsArePreserved(String conflict) throws Exception {
        history(Map.of("assets",object("old")),Map.of("assets/file",object("head"))); write("assets/file","head");
        Path junction=null;
        switch(conflict) {
            case "untracked","ignored" -> write("assets/extra","preserve");
            case "empty-directory" -> Files.createDirectory(root.resolve("assets/empty"));
            case "junction" -> { Path outside=Files.createDirectory(root.resolve("outside")); write("outside/sentinel","preserve"); junction=root.resolve("assets/link"); WindowsMetadataBoundaryTest.junction(junction,outside); }
        }
        if(conflict.equals("ignored")) write(".vrnignore","assets/extra\n");
        var before=RepositoryPreflightTest.tree(root);
        try { assertThrows(Exception.class,()->new RestoreService(root).restore("old")); assertEquals(before,RepositoryPreflightTest.tree(root)); noPending(); }
        finally { if(junction!=null) Files.delete(junction); }
    }

    @Test void replacementPreservesExistingFileSecurityAndDosFlags() throws Exception {
        replacement(); Path file=root.resolve("Alpha");
        var before=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        var acl=before.getAcl(); var owner=before.getOwner(); Files.setAttribute(file,"dos:hidden",true,LinkOption.NOFOLLOW_LINKS);
        new RestoreService(root).restore("old");
        var after=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        assertEquals(acl,after.getAcl()); assertEquals(owner,after.getOwner());
        assertTrue(Files.readAttributes(file,java.nio.file.attribute.DosFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isHidden());
    }
}
