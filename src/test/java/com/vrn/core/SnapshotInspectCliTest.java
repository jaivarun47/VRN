package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotInspectCliTest {
    @TempDir Path temporary;

    SnapshotLogCliTest fixture() throws Exception {
        var cli = new SnapshotLogCliTest();
        cli.temporary = temporary;
        cli.initialize();
        return cli;
    }

    @Test void emptyHeadIsNotASelectedSnapshot() throws Exception {
        var cli = fixture();
        var before = RepositoryPreflightTest.tree(cli.root);
        var result = cli.run(cli.root, "inspect", "HEAD");
        assertEquals(1, result.code(), result.err());
        assertEquals("", result.out());
        assertTrue(result.err().contains("No current snapshot"), result.err());
        assertEquals(before, RepositoryPreflightTest.tree(cli.root));
    }

    @Test void emptyManifestCanBeInspectedWithExactMetadata() throws Exception {
        var cli = fixture();
        cli.snapshot("first", "NONE", 0, "日本語\nmessage");
        Files.writeString(cli.storage.resolve("HEAD"), "first");
        var before = RepositoryPreflightTest.tree(cli.root);
        var result = cli.run(cli.root, "inspect", "HEAD");
        assertEquals(0, result.code(), result.err());
        assertEquals("snapshot first\nParent: NONE\nDate: 1970-01-01T00:00:00Z\nMessage: \"日本語\\nmessage\"\nObjects: verified\nFiles:\n  (none)\n", result.out());
        assertEquals(before, RepositoryPreflightTest.tree(cli.root));
    }

    @Test void historicalSelectionListsExactPathsHashesAndStableOrder() throws Exception {
        var cli = fixture();
        Files.writeString(cli.root.resolve("source.txt"), "content");
        new AddService(cli.root).add(Path.of("source.txt"));
        new CommitService(cli.storage).commit("Initial snapshot");
        String hash = new SnapshotStore(cli.storage).load(Files.readString(cli.storage.resolve("HEAD"))).files().get("source.txt");
        Files.writeString(cli.storage.resolve("snapshots/old.snapshot"), MetadataCodec.encodeSnapshot(new Snapshot("old", "NONE", 0, "historical", Map.of("src/日本語.java", hash, "README.md", hash))));
        cli.snapshot("new", "old", 1, "newest");
        Files.writeString(cli.storage.resolve("HEAD"), "new");
        var before = RepositoryPreflightTest.tree(cli.root);
        var result = cli.run(cli.root, "inspect", "old");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().startsWith("snapshot old\nParent: NONE\n"));
        assertTrue(result.out().contains(hash + "  \"src/日本語.java\""), result.out());
        assertTrue(result.out().indexOf("README.md") < result.out().indexOf("src/日本語.java"));
        assertEquals(before, RepositoryPreflightTest.tree(cli.root));
    }

    @ParameterizedTest @ValueSource(strings={"missing-object","digest","directory","pending","missing-parent","cycle","unreachable","version","invalid-selector"})
    void failedInspectionPrintsNoPartialSuccessAndChangesNothing(String fault) throws Exception {
        var cli=fixture(); var f=new SnapshotInspectionTest(); f.root=cli.root; f.storage=cli.storage;
        String hash=f.object("bytes"); f.history(Map.of("file.txt",hash),Map.of());
        String selector="old";
        switch(fault) {
            case "missing-object" -> Files.delete(cli.storage.resolve("objects/"+hash));
            case "digest" -> Files.writeString(cli.storage.resolve("objects/"+hash),"corrupt");
            case "directory" -> { Files.delete(cli.storage.resolve("objects/"+hash)); Files.createDirectory(cli.storage.resolve("objects/"+hash)); }
            case "pending" -> Files.writeString(cli.storage.resolve("staging/commit.pending"),"pending remains");
            case "missing-parent" -> Files.delete(cli.storage.resolve("snapshots/old.snapshot"));
            case "cycle" -> f.snapshot("old","current",Map.of());
            case "unreachable" -> { f.snapshot("orphan","NONE",Map.of()); selector="orphan"; }
            case "version" -> Files.writeString(cli.storage.resolve("snapshots/old.snapshot"),"VRN-SNAPSHOT\t99\n");
            case "invalid-selector" -> selector="../escape";
        }
        Files.deleteIfExists(cli.storage.resolve("mutation.lock"));
        var before=RepositoryPreflightTest.tree(cli.root);
        var result=cli.run(cli.root,"inspect",selector);
        assertEquals(1,result.code(),result.err()); assertEquals("",result.out());
        assertTrue(result.err().contains("Failed to inspect snapshot"),result.err());
        assertFalse(result.err().contains(cli.storage.toString()));
        assertEquals(before,RepositoryPreflightTest.tree(cli.root));
    }

    @ParameterizedTest @ValueSource(strings={"missing","extra","force","oneline","help"})
    void unsupportedArgumentsAreRejected(String kind) throws Exception {
        var cli=fixture();
        String[] args=switch(kind) {
            case "missing" -> new String[]{"inspect"};
            case "extra" -> new String[]{"inspect","HEAD","extra"};
            case "force" -> new String[]{"inspect","HEAD","--force"};
            case "oneline" -> new String[]{"inspect","HEAD","--oneline"};
            default -> new String[]{"inspect","--help"};
        };
        var before=RepositoryPreflightTest.tree(cli.root); var result=cli.run(cli.root,args);
        assertEquals(2,result.code()); assertEquals("",result.out()); assertEquals(before,RepositoryPreflightTest.tree(cli.root));
    }

    @Test void outsideAndNestedDirectoriesDoNotDiscoverParents() throws Exception {
        var cli=fixture(); Path nested=Files.createDirectory(cli.root.resolve("nested"));
        for(Path cwd:List.of(temporary,nested)) { var result=cli.run(cwd,"inspect","HEAD"); assertEquals(1,result.code()); assertTrue(result.err().contains("Not a VRN repository")); }
    }

    @Test void threeRealSnapshotsIncludeContentChangesDeletionAndCaseTransition() throws Exception {
        var cli=fixture(); Files.writeString(cli.root.resolve("Main.txt"),"initial"); Files.writeString(cli.root.resolve("obsolete.txt"),"delete later");
        assertEquals(0,cli.run(cli.root,"add",".").code()); assertEquals(0,cli.run(cli.root,"commit","-m","Initial snapshot").code());
        String first=Files.readString(cli.storage.resolve("HEAD"));
        Files.writeString(cli.root.resolve("Main.txt"),"second"); Files.writeString(cli.root.resolve("added.txt"),"added");
        assertEquals(0,cli.run(cli.root,"stage-delete","obsolete.txt").code()); Files.delete(cli.root.resolve("obsolete.txt"));
        assertEquals(0,cli.run(cli.root,"add",".").code()); assertEquals(0,cli.run(cli.root,"commit","-m","Changed added deleted").code());
        Files.move(cli.root.resolve("Main.txt"),cli.root.resolve("temporary-name")); Files.move(cli.root.resolve("temporary-name"),cli.root.resolve("MAIN.txt"));
        assertEquals(0,cli.run(cli.root,"add","MAIN.txt").code()); assertEquals(0,cli.run(cli.root,"commit","-m","Case transition").code());
        Files.delete(cli.storage.resolve("mutation.lock")); var before=RepositoryPreflightTest.tree(cli.root);
        var log=cli.run(cli.root,"log"); var head=cli.run(cli.root,"inspect","HEAD"); var old=cli.run(cli.root,"inspect",first);
        assertEquals(0,log.code(),log.err()); assertEquals(3,log.out().lines().filter(line->line.startsWith("commit ")).count());
        assertEquals(0,head.code(),head.err()); assertTrue(head.out().contains("\"MAIN.txt\"")); assertTrue(head.out().contains("\"added.txt\"")); assertFalse(head.out().contains("obsolete.txt"));
        assertEquals(0,old.code(),old.err()); assertTrue(old.out().contains("\"Main.txt\"")); assertTrue(old.out().contains("\"obsolete.txt\""));
        var probe=probe(cli.root,first); assertTrue(probe.contains("READ_ONLY=true")); assertTrue(probe.contains("REPLACE SAFE Main.txt"),probe);
        assertEquals(before,RepositoryPreflightTest.tree(cli.root));
    }

    String probe(Path root,String selector) throws Exception {
        Path out=temporary.resolve("probe.out"),err=temporary.resolve("probe.err");
        String cp=Path.of(RestorePlanProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                +java.io.File.pathSeparator+System.getProperty("vrn.executableJar");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Xmx64m","-cp",cp,RestorePlanProbe.class.getName(),root.toString(),selector)
                .directory(root.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            assertTrue(process.waitFor(120,TimeUnit.SECONDS),"Constrained plan probe timed out");
            assertEquals(0,process.exitValue(),Files.readString(err)); return Files.readString(out);
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }

    @Test void largeObjectAndWorkingFileAreVerifiedUnderConstrainedHeap() throws Exception {
        var cli=fixture(); StreamingCaptureCliTest.generate(cli.root.resolve("large.bin"),80L*1024*1024,811);
        assertEquals(0,cli.run(cli.root,"add","large.bin").code()); assertEquals(0,cli.run(cli.root,"commit","-m","Large snapshot").code());
        Files.delete(cli.storage.resolve("mutation.lock")); var before=RestorePlanProbe.fingerprint(cli.root);
        var inspect=cli.run(cli.root,"inspect","HEAD"); assertEquals(0,inspect.code(),inspect.err()); assertTrue(inspect.out().contains("large.bin"));
        String plan=probe(cli.root,"HEAD"); assertTrue(plan.contains("SAFE=true")); assertTrue(plan.contains("KEEP SAFE large.bin"));
        assertEquals(before,RestorePlanProbe.fingerprint(cli.root)); assertFalse(Files.exists(cli.storage.resolve("mutation.lock")));
    }
}
