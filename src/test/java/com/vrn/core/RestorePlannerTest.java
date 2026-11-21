package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.vrn.core.RestorePlan.*;
import static org.junit.jupiter.api.Assertions.*;

class RestorePlannerTest {
    @TempDir Path root;
    SnapshotInspectionTest fixture;
    Path storage;
    @BeforeEach void initialize() throws Exception {
        fixture=new SnapshotInspectionTest(); fixture.root=root; fixture.initialize(); storage=fixture.storage;
    }
    String object(String text) throws Exception { return fixture.object(text); }
    void history(Map<String,String> old,Map<String,String> current) throws Exception { fixture.history(old,current); }
    Path write(String path,String text) throws Exception { return fixture.write(path,text); }
    RestorePlan plan() throws Exception {
        Files.deleteIfExists(storage.resolve("mutation.lock"));
        var before=RepositoryPreflightTest.tree(root);
        var plan=new RestorePlanner(root).plan("old");
        assertEquals(before,RepositoryPreflightTest.tree(root));
        assertFalse(Files.exists(storage.resolve("mutation.lock")));
        return plan;
    }
    Action action(RestorePlan plan,String path) { return plan.actions().stream().filter(a->a.path().equals(path)).findFirst().orElseThrow(); }
    ExpectedPath expected(RestorePlan plan,String path) { return plan.expectedPaths().stream().filter(a->a.requestedPath().equals(path)).findFirst().orElseThrow(); }

    @Test void matchingTargetKeepsFilesAndPreservesUnrelatedWorkingAndStagedPaths() throws Exception {
        String hash=object("same"); history(Map.of("src/Main.java",hash),Map.of("src/Main.java",hash));
        write("src/Main.java","same"); write("untracked.txt","untracked"); write("ignored.txt","ignored");
        write(".vrnignore","ignored.txt\n.vrn/\n"); write("staged-only.txt","selection");
        write(".vrnauto/sentinel","protected");
        new AddService(root).add(Path.of("staged-only.txt"));
        var plan=plan();
        assertTrue(plan.isSafe()); assertEquals(1,plan.actions().size());
        assertEquals(Operation.KEEP,action(plan,"src/Main.java").operation());
        assertFalse(action(plan,"src/Main.java").requiresChange());
        assertTrue(plan.selection().staging().get("staged-only.txt") instanceof StagedOperation.Put);
        assertEquals("current",plan.selection().headId()); assertEquals("old",plan.selection().target().id());
        assertThrows(UnsupportedOperationException.class,()->plan.actions().clear());
        assertThrows(UnsupportedOperationException.class,()->plan.expectedPaths().clear());
        assertThrows(UnsupportedOperationException.class,()->plan.selection().staging().clear());
    }

    @Test void olderContentPlansSafeReplacementWithDigestPreconditions() throws Exception {
        String old=object("older"),current=object("current"); history(Map.of("file",old),Map.of("file",current)); write("file","current");
        var plan=plan(); var action=action(plan,"file");
        assertTrue(plan.isSafe()); assertEquals(Operation.REPLACE,action.operation());
        assertEquals(current,action.headHash()); assertEquals(old,action.targetHash());
        assertEquals(current,expected(plan,"file").contentHash());
        assertNotNull(expected(plan,"file").modified()); assertNotNull(expected(plan,"file").created());
        assertEquals(Type.DIRECTORY,expected(plan,"").type());
    }

    @Test void missingTargetFileRecordsEveryAbsentIntermediateDirectory() throws Exception {
        history(Map.of("deep/nested/file",object("target")),Map.of());
        var plan=plan(); assertTrue(plan.isSafe()); assertEquals(Operation.CREATE,action(plan,"deep/nested/file").operation());
        for(String path:List.of("deep","deep/nested","deep/nested/file")) assertEquals(Type.MISSING,expected(plan,path).type());
    }

    @ParameterizedTest @ValueSource(strings={"clean","modified","absent","directory"})
    void headOnlyRemovalIsOwnedAndConservative(String state) throws Exception {
        history(Map.of(),Map.of("gone",object("clean")));
        if(state.equals("clean")||state.equals("modified")) write("gone",state);
        if(state.equals("directory")) write("gone/untracked","preserve");
        var action=action(plan(),"gone"); assertEquals(Operation.REMOVE,action.operation());
        assertEquals(state.equals("modified")?Safety.CONFLICT:Safety.SAFE,action.safety());
        assertEquals(state.equals("clean")||state.equals("modified"),action.requiresChange());
    }

    @ParameterizedTest @ValueSource(strings={"overwrite-modified","already-target"})
    void modifiedTrackedBytesAreOnlySafeWhenNoChangeIsRequired(String state) throws Exception {
        history(Map.of("file",object("old")),Map.of("file",object("head")));
        write("file",state.equals("already-target")?"old":"local edits");
        var action=action(plan(),"file");
        assertEquals(state.equals("already-target")?Safety.SAFE:Safety.CONFLICT,action.safety());
        if(state.equals("already-target")) assertEquals(Operation.KEEP,action.operation());
    }

    @ParameterizedTest @ValueSource(strings={"ordinary","ignored","staged-only","same-bytes","directory","empty-directory"})
    void targetDestinationNeverAdoptsOrDiscardsUntrackedEntries(String kind) throws Exception {
        history(Map.of("slot",object("target")),Map.of());
        if(kind.equals("directory")) write("slot/child","untracked");
        else if(kind.equals("empty-directory")) Files.createDirectory(root.resolve("slot"));
        else write("slot",kind.equals("same-bytes")?"target":"untracked");
        if(kind.equals("ignored")) write(".vrnignore","slot\n");
        if(kind.equals("staged-only")) new AddService(root).add(Path.of("slot"));
        assertEquals(Safety.CONFLICT,action(plan(),"slot").safety());
    }

    @ParameterizedTest @ValueSource(strings={"put","delete","keep-with-put"})
    void relatedStagingIntentIsPreservedAsAConflict(String kind) throws Exception {
        String old=object(kind.equals("keep-with-put")?"head":"old"),head=object("head");
        history(Map.of("file",old),Map.of("file",head)); write("file","head");
        Map<String,StagedOperation> entries=Map.of("file",kind.equals("delete")?new StagedOperation.Delete():new StagedOperation.Put(object("staged")));
        Files.writeString(storage.resolve("staging/index"),MetadataCodec.encodeOperations(entries));
        var plan=plan(); assertEquals(Safety.CONFLICT,action(plan,"file").safety()); assertEquals(entries,plan.selection().staging());
    }

    @ParameterizedTest @ValueSource(strings={"file-dir","dir-file","nested-file-dir","nested-dir-file"})
    void cleanStructuralTransitionsHaveExplicitPrerequisites(String kind) throws Exception {
        String old=object("old"),head=object("head");
        boolean toDirectory=kind.endsWith("file-dir"); boolean nested=kind.startsWith("nested");
        String flat=nested?"node/nested":"node", child=flat+"/child/data";
        String target=toDirectory?child:flat, current=toDirectory?flat:child;
        history(Map.of(target,old),Map.of(current,head)); write(current,"head");
        var plan=plan(); assertTrue(plan.isSafe(),plan.actions().toString());
        assertEquals(Operation.REMOVE,action(plan,current).operation());
        assertEquals(toDirectory?Operation.CREATE:Operation.REPLACE,action(plan,target).operation());
        assertTrue(action(plan,target).prerequisites().contains(current));
        if(toDirectory) assertEquals(flat,expected(plan,child).blockingAncestor());
        else assertNotNull(expected(plan,flat).children());
    }

    @ParameterizedTest @ValueSource(strings={"modified","untracked","ignored","empty-untracked-dir","staged"})
    void structuralReplacementDoesNotDiscardObstructions(String kind) throws Exception {
        history(Map.of("assets",object("target")),Map.of("assets/logo",object("head"))); write("assets/logo",kind.equals("modified")?"local":"head");
        if(kind.equals("untracked")||kind.equals("ignored")) write("assets/extra","preserve");
        if(kind.equals("ignored")) write(".vrnignore","assets/extra\n");
        if(kind.equals("empty-untracked-dir")) Files.createDirectory(root.resolve("assets/empty"));
        if(kind.equals("staged")) Files.writeString(storage.resolve("staging/index"),MetadataCodec.encodeOperations(Map.of("assets/logo",new StagedOperation.Delete())));
        assertEquals(Safety.CONFLICT,action(plan(),"assets").safety());
    }

    @ParameterizedTest @ValueSource(strings={"file","directory"})
    void caseOnlyTransitionsKeepExactSpellingWithoutRenamingAnything(String kind) throws Exception {
        String old=kind.equals("file")?"HELLO.txt":"SRC/Main.java",head=kind.equals("file")?"hello.txt":"src/Main.java";
        String hash=object("same"); history(Map.of(old,hash),Map.of(head,hash)); write(head,"same");
        var plan=plan(); assertTrue(plan.isSafe(),plan.actions().toString());
        var action=action(plan,old); assertEquals(Operation.REPLACE,action.operation()); assertEquals(head,action.currentPath());
        if(kind.equals("directory")) assertEquals(List.of(new DirectoryChange("src","SRC")),plan.directoryChanges());
    }

    @ParameterizedTest @ValueSource(strings={"untracked","modified-sibling","staged-sibling"})
    void directorySpellingTransitionExaminesAllAffectedMembers(String kind) throws Exception {
        String hash=object("head"); history(Map.of("SRC/a",hash,"SRC/b",hash),Map.of("src/a",hash,"src/b",hash));
        write("src/a","head"); write("src/b",kind.equals("modified-sibling")?"local":"head");
        if(kind.equals("untracked")) write("src/extra","preserve");
        if(kind.equals("staged-sibling")) Files.writeString(storage.resolve("staging/index"),MetadataCodec.encodeOperations(Map.of("src/b",new StagedOperation.Put(hash))));
        var plan=plan(); assertEquals(Safety.CONFLICT,action(plan,"SRC/a").safety());
    }

    @Test void historicalSharedDirectoryAliasesAreInspectableButNotMaterializableAsTwoSpellings() throws Exception {
        String hash=object("data"); history(Map.of("src/a",hash,"SRC/b",hash),Map.of());
        assertEquals(2,new SnapshotInspector(storage).inspect("old").files().size());
        var plan=plan(); assertFalse(plan.isSafe()); assertTrue(plan.actions().stream().allMatch(a->a.safety()==Safety.UNSUPPORTED));
    }

    @ParameterizedTest @ValueSource(strings={"file-changed","file-removed","destination-occupied","directory-link"})
    void immutablePreconditionsExposeStalenessWithoutExecutingARestore(String change) throws Exception {
        String hash=object("head");
        if(change.equals("destination-occupied")) history(Map.of("file",hash),Map.of());
        else { history(Map.of("dir/file",hash),Map.of("dir/file",hash)); write("dir/file","head"); }
        var plan=plan();
        switch(change) {
            case "file-changed" -> { write("dir/file","changed"); assertNotEquals(expected(plan,"dir/file").contentHash(),new FileHasher().hash(root.resolve("dir/file"))); }
            case "file-removed" -> { Files.delete(root.resolve("dir/file")); assertEquals(Type.FILE,expected(plan,"dir/file").type()); }
            case "destination-occupied" -> { write("file","new untracked"); assertEquals(Type.MISSING,expected(plan,"file").type()); }
            case "directory-link" -> {
                Path outside=Files.createDirectory(root.resolve("outside")); write("outside/sentinel","preserve");
                Files.move(root.resolve("dir"),root.resolve("saved")); WindowsMetadataBoundaryTest.junction(root.resolve("dir"),outside);
                try {
                    assertEquals(Type.DIRECTORY,expected(plan,"dir").type());
                    assertEquals(Safety.UNSUPPORTED,action(new RestorePlanner(root).plan("old"),"dir/file").safety());
                    assertEquals("preserve",Files.readString(outside.resolve("sentinel")));
                } finally { Files.delete(root.resolve("dir")); }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"head","staging","pending"})
    void repositoryChangesDuringHashingInvalidateTheObservation(String change) throws Exception {
        String hash=object("head"); history(Map.of("file",hash),Map.of("file",hash)); write("file","head");
        if(change.equals("head")) { write("extra","extra"); new AddService(root).add(Path.of("extra")); }
        AtomicBoolean once=new AtomicBoolean();
        var changing=new FileHasher() {
            @Override public String hash(Path file) throws Exception {
                String result=super.hash(file);
                if(once.compareAndSet(false,true)) switch(change) {
                    case "head" -> new CommitService(storage).commit("concurrent publication");
                    case "staging" -> Files.writeString(storage.resolve("staging/index"),MetadataCodec.encodeOperations(Map.of("file",new StagedOperation.Put(hash))));
                    case "pending" -> Files.writeString(storage.resolve("staging/commit.pending"),"external pending record");
                }
                return result;
            }
        };
        Exception failure=assertThrows(IOException.class,()->new RestorePlanner(root,new SnapshotInspector(storage),changing).plan("old"));
        assertTrue(failure.getMessage().contains("changed")||failure.getMessage().contains("Pending"),failure.getMessage());
    }

    @Test void semanticallyInvalidStagingCannotProduceASafePlan() throws Exception {
        history(Map.of(),Map.of()); Files.writeString(storage.resolve("staging/index"),MetadataCodec.encodeOperations(Map.of("unknown",new StagedOperation.Delete())));
        var before=RepositoryPreflightTest.tree(root);
        assertThrows(Exception.class,()->new RestorePlanner(root).plan("old")); assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @ParameterizedTest @ValueSource(strings={"ambiguous","sensitive-alias"})
    void unsupportedCaseSensitiveDestinationsAreNotGuessed(String kind) throws Exception {
        String hash=object("head");
        history(Map.of(kind.equals("ambiguous")?"scope/a":"scope/HELLO",hash),Map.of(kind.equals("ambiguous")?"scope/a":"scope/hello",hash));
        Path directory=Files.createDirectory(root.resolve("scope"));
        Process enable=new ProcessBuilder("fsutil.exe","file","setCaseSensitiveInfo",directory.toString(),"enable").redirectErrorStream(true).start();
        assertTrue(enable.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        org.junit.jupiter.api.Assumptions.assumeTrue(enable.exitValue()==0,"Case-sensitive test directory unavailable");
        write(kind.equals("ambiguous")?"scope/a":"scope/hello","head");
        if(kind.equals("ambiguous")) write("scope/A","other");
        var plan=plan();
        assertFalse(plan.isSafe()); assertTrue(plan.actions().stream().anyMatch(a->a.safety()==Safety.UNSUPPORTED));
    }

    @Test void detectableWorkingFileChangeDuringHashingIsUnsupported() throws Exception {
        String hash=object("head"); history(Map.of("file",hash),Map.of("file",hash)); write("file","head");
        var changing=new FileHasher() { @Override public String hash(Path file) throws Exception { String result=super.hash(file); Files.writeString(file,"changed content"); return result; } };
        var plan=new RestorePlanner(root,new SnapshotInspector(storage),changing).plan("old");
        assertEquals(Safety.UNSUPPORTED,action(plan,"file").safety());
    }

    @Test void linkedDescendantCannotBeConsumedByDirectoryReplacement() throws Exception {
        String hash=object("head"); history(Map.of("assets",hash),Map.of("assets/file",hash)); write("assets/file","head");
        Path outside=Files.createDirectory(root.resolve("outside")); write("outside/sentinel","preserve");
        Path junction=root.resolve("assets/link"); WindowsMetadataBoundaryTest.junction(junction,outside);
        try {
            var plan=plan(); assertEquals(Safety.UNSUPPORTED,action(plan,"assets").safety());
            assertEquals(Type.UNSUPPORTED,expected(plan,"assets/link").type());
            assertEquals("preserve",Files.readString(outside.resolve("sentinel")));
        } finally { Files.delete(junction); }
    }
}
