package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StatusCorrectnessTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({
            "-, -, A, false, false, false, false, true",
            "-, A, A, true, false, false, false, false",
            "-, A, B, true, false, true, false, false",
            "-, A, -, true, false, false, true, false",
            "A, -, A, false, false, false, false, false",
            "A, -, B, false, false, true, false, false",
            "A, -, -, false, false, false, true, false",
            "A, A, A, false, false, false, false, false",
            "A, A, -, false, false, false, true, false",
            "A, B, B, false, true, false, false, false",
            "A, B, C, false, true, true, false, false",
            "A, B, -, false, true, false, true, false",
            "A, A, B, false, false, true, false, false",
            "A, B, A, false, true, true, false, false",
            "-, -, -, false, false, false, false, false"
    })
    void independentlyClassifiesHeadIndexAndWorkingTree(String head, String index, String working,
            boolean addition, boolean stagedModification, boolean modification, boolean deletion, boolean untracked) throws Exception {
        prepare(root, "a.txt", head, index, working, ".vrn/\n");
        var before=RepositoryPreflightTest.tree(root.resolve(".vrn"));
        WorkingTreeStatus status=WorkingTreeStatus.compute(root);
        assertCategories(status,"a.txt",addition,stagedModification,modification,deletion,untracked);
        assertEquals(before,RepositoryPreflightTest.tree(root.resolve(".vrn")));
    }

    @ParameterizedTest
    @CsvSource({
            "A, -, A, false, false, false, false",
            "A, -, B, false, false, true, false",
            "A, -, -, false, false, false, true",
            "A, A, A, false, false, false, false",
            "A, A, -, false, false, false, true",
            "A, B, B, false, true, false, false",
            "A, B, C, false, true, true, false",
            "-, A, A, true, false, false, false",
            "-, A, -, true, false, false, true"
    })
    void ignoredDirectoryKeepsTrackedAndStagedFilesVisible(String head,String index,String working,
            boolean addition,boolean stagedModification,boolean modification,boolean deletion) throws Exception {
        prepare(root,"build/config.txt",head,index,working,".vrn/\nbuild/\n*.tmp\n");
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("build/untracked.txt"),"ignored untracked content");
        Files.writeString(root.resolve("debug.tmp"),"also ignored");
        assertCategories(WorkingTreeStatus.compute(root),"build/config.txt",addition,stagedModification,modification,deletion,false);
    }

    @Test void newlyIgnoredTrackedFileIsComparedInsteadOfReportedDeleted() throws Exception {
        prepare(root,"a.txt","A","-","A",".vrn/\n");
        Files.writeString(root.resolve(".vrnignore"),".vrn/\na.txt\n");
        var status=WorkingTreeStatus.compute(root);
        assertTrue(status.deleted.isEmpty());
        assertEquals(List.of(".vrnignore"),status.modifiedNotStaged);
        Files.writeString(root.resolve("a.txt"),"B");
        assertEquals(List.of(".vrnignore","a.txt"),WorkingTreeStatus.compute(root).modifiedNotStaged);
    }

    @Test void missingOptionalIgnoreStillReportsAnUnchangedStagedFileDeletion() throws Exception {
        prepare(root,"a.txt","A","A","-",".vrn/\n");
        Files.delete(root.resolve(".vrnignore"));
        var status=WorkingTreeStatus.compute(root);
        assertEquals(List.of(".vrnignore","a.txt"),status.deleted);
        assertFalse(status.isClean());
        assertFalse(Files.exists(root.resolve(".vrnignore")));
    }

    @Test void distinctUnicodeSpellingsAreNotNormalizedOrCollapsed() throws Exception {
        new RepositoryInitializer().initialize(root);
        String composed="é.txt", decomposed="e\u0301.txt";
        Files.writeString(root.resolve(composed),"first");
        Files.writeString(root.resolve(decomposed),"second");
        installHead(root,Map.of(composed,digest("first"),decomposed,digest("second")));
        assertTrue(WorkingTreeStatus.compute(root).isClean());
        Files.writeString(root.resolve(decomposed),"changed");
        assertEquals(List.of(decomposed),WorkingTreeStatus.compute(root).modifiedNotStaged);
    }

    @Test void regularFileReplacedByDirectoryIsAWorkingDeletionWithUntrackedChildren() throws Exception {
        prepare(root,"node","A","-","-",".vrn/\n");
        Files.createDirectory(root.resolve("node"));
        Files.writeString(root.resolve("node/child.txt"),"child");
        var status=WorkingTreeStatus.compute(root);
        assertEquals(List.of("node"),status.deleted);
        assertEquals(List.of("node/child.txt"),status.untracked);
    }

    @Test void cliReportsFalseCleanRegressionAsWorkingDeletion() throws Exception {
        prepare(root,"a.txt","A","A","-",".vrn/\n");
        var result=RepositoryPreflightTest.cli(root,"status");
        assertEquals(0,result.code(),result.output());
        assertTrue(result.output().contains("deleted:      a.txt"),result.output());
        assertFalse(result.output().contains("working directory clean"),result.output());
    }

    @Test void trackedReadFailureIsNotConvertedToDeletionOrClean() throws Exception {
        prepare(root,"a.txt","A","A","A",".vrn/\n");
        var before=RepositoryPreflightTest.tree(root.resolve(".vrn"));
        FileHasher denied=new FileHasher() {
            @Override public String hash(Path file) throws Exception {
                if(file.getFileName().toString().equals("a.txt")) throw new AccessDeniedException(file.toString());
                return super.hash(file);
            }
        };
        assertThrows(AccessDeniedException.class,()->WorkingTreeStatus.compute(root,denied));
        assertEquals(before,RepositoryPreflightTest.tree(root.resolve(".vrn")));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void cooperatingMetadataChangeDuringUnlockedHashingRequiresRetry(boolean commit) throws Exception {
        prepare(root,"a.txt","A","-","A",".vrn/\n");
        Path storage=root.resolve(".vrn");
        for(String file:List.of("a.txt",".vrnignore")) {
            var captured=new FileHasher().hashWithBytes(root.resolve(file));
            new ObjectStore(storage).store(captured.hash(),captured.bytes());
        }
        FileHasher interleave=new FileHasher() {
            boolean changed;
            @Override public String hash(Path file) throws Exception {
                if(!changed && file.getFileName().toString().equals("a.txt")) {
                    changed=true;
                    new StagingArea(storage).stage(Path.of("a.txt"),digest("A"));
                    if(commit) new CommitService(storage).commit("same file map, new HEAD");
                }
                return super.hash(file);
            }
        };
        IOException error=assertThrows(IOException.class,()->WorkingTreeStatus.compute(root,interleave));
        assertTrue(error.getMessage().contains("metadata changed during status"),error.getMessage());
        if(commit) {
            assertNotEquals("initial",Files.readString(storage.resolve("HEAD")));
            assertTrue(new StagingArea(storage).readAll().isEmpty());
        } else {
            assertEquals(Map.of("a.txt",digest("A")),new StagingArea(storage).readAll());
        }
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @ParameterizedTest @ValueSource(strings={"config","index","head","snapshot"})
    void invalidMetadataCannotBecomeCleanOutputOrMutateRepository(String item) throws Exception {
        prepare(root,"a.txt","A","-","A",".vrn/\n");
        Path storage=root.resolve(".vrn");
        switch(item) {
            case "config" -> Files.writeString(storage.resolve("config"),"version=999");
            case "index" -> Files.writeString(storage.resolve("staging/index"),"VRN-STAGING\t999\n");
            case "head" -> Files.delete(storage.resolve("HEAD"));
            case "snapshot" -> Files.writeString(storage.resolve("snapshots/initial.snapshot"),"VRN-SNAPSHOT\t999\n");
            default -> throw new AssertionError(item);
        }
        var before=RepositoryPreflightTest.tree(storage);
        assertThrows(Exception.class,()->WorkingTreeStatus.compute(root));
        var result=RepositoryPreflightTest.cli(root,"status");
        assertEquals(1,result.code(),result.output());
        assertFalse(result.output().contains("working directory clean"));
        assertEquals(before,RepositoryPreflightTest.tree(storage));
    }

    static void prepare(Path root,String path,String head,String index,String working,String rules) throws Exception {
        new RepositoryInitializer().initialize(root);
        Files.writeString(root.resolve(".vrnignore"),rules);
        Map<String,String> files=new LinkedHashMap<>();
        if(!head.equals("-")) files.put(path,digest(head));
        installHead(root,files);
        if(!index.equals("-")) new StagingArea(root.resolve(".vrn")).stage(Path.of(path),digest(index));
        if(!working.equals("-")) {
            Path file=root.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file,working);
        }
    }

    static void installHead(Path root,Map<String,String> entries) throws Exception {
        Map<String,String> files=new LinkedHashMap<>(entries);
        files.put(".vrnignore",new FileHasher().hash(root.resolve(".vrnignore")));
        new SnapshotStore(root.resolve(".vrn")).save(new Snapshot("initial","NONE",1,"fixture",files));
        Files.writeString(root.resolve(".vrn/HEAD"),"initial");
    }
    static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    static void assertCategories(WorkingTreeStatus s,String path,boolean add,boolean stage,boolean mod,boolean deleted,boolean untracked) {
        assertEquals(add?List.of(path):List.of(),s.stagedNewFiles,"staged addition");
        assertEquals(stage?List.of(path):List.of(),s.stagedModifications,"staged modification");
        assertEquals(mod?List.of(path):List.of(),s.modifiedNotStaged,"working modification");
        assertEquals(deleted?List.of(path):List.of(),s.deleted,"working deletion");
        assertEquals(untracked?List.of(path):List.of(),s.untracked,"untracked");
        assertEquals(!(add||stage||mod||deleted||untracked),s.isClean());
    }
}
