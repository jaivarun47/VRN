package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotLogCliTest {
    @TempDir Path temporary;
    Path root;
    Path storage;

    void initialize() throws Exception {
        root=Files.createDirectory(temporary.resolve("project"));
        new RepositoryInitializer().initialize(root);
        storage=root.resolve(".vrn");
    }
    void snapshot(String id, String parent, long time, String message) throws Exception {
        Files.writeString(storage.resolve("snapshots/"+id+".snapshot"), MetadataCodec.encodeSnapshot(new Snapshot(id,parent,time,message,Map.of())));
    }

    @Test void emptyLogSucceedsWithoutCreatingEvenALockFile() throws Exception {
        initialize();
        Files.deleteIfExists(storage.resolve("mutation.lock"));
        var before=RepositoryPreflightTest.tree(root);
        var result=run(root,"log");
        assertEquals(0,result.code(),result.err());
        assertEquals("No snapshots found.\n",result.out().replace("\r\n","\n"));
        assertEquals(before,RepositoryPreflightTest.tree(root));
        assertFalse(Files.exists(storage.resolve("mutation.lock")));
    }

    @Test void singleSnapshotDisplaysRecordedMetadataInUtc() throws Exception {
        initialize();
        snapshot("first","NONE",0,"Initial snapshot");
        Files.writeString(storage.resolve("HEAD"),"first");
        var before=RepositoryPreflightTest.tree(root);
        var result=run(root,"log");
        assertEquals(0,result.code(),result.err());
        assertEquals("commit first\nParent: NONE\nDate: 1970-01-01T00:00:00Z\nMessage: \"Initial snapshot\"\n\n",result.out().replace("\r\n","\n"));
        assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @Test void parentOrderAndUnicodeMessagesAreAuthoritativeAndCannotSpoofEntries() throws Exception {
        initialize();
        snapshot("zulu","NONE",2000,"日本語 café");
        snapshot("alpha","zulu",1000,"  message=ok\ncommit forged\r\t\u001b[31m\"\\");
        snapshot("orphan","NONE",9000,"must not appear");
        Files.writeString(storage.resolve("HEAD"),"alpha");
        var before=RepositoryPreflightTest.tree(root);
        var result=run(root,"log");
        assertEquals(0,result.code(),result.err());
        assertTrue(result.out().startsWith("commit alpha\nParent: zulu\n"),result.out());
        assertTrue(result.out().contains("日本語 café"),result.out());
        assertTrue(result.out().contains("\\ncommit forged\\r\\t\\u001b[31m"),result.out());
        assertEquals(2,result.out().lines().filter(line->line.startsWith("commit ")).count());
        assertFalse(result.out().contains("must not appear"));
        assertFalse(result.out().contains("\u001b"));
        assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    Run run(Path cwd,String...args) throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
                "-Xmx64m","-Xss256k","-jar",System.getProperty("vrn.executableJar")));
        command.addAll(List.of(args));
        Path out=Files.createTempFile(temporary,"log-out-",".txt");
        Path err=Files.createTempFile(temporary,"log-err-",".txt");
        Process process=new ProcessBuilder(command).directory(cwd.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try { assertTrue(process.waitFor(120,TimeUnit.SECONDS),"log CLI timeout"); return new Run(process.exitValue(),Files.readString(out),Files.readString(err)); }
        finally { if(process.isAlive()) process.destroyForcibly(); }
    }

    @ParameterizedTest @ValueSource(strings={"extra","--oneline","-n","--unknown"})
    void unsupportedArgumentsAreRejectedWithoutWrites(String argument) throws Exception {
        initialize();
        var before=RepositoryPreflightTest.tree(root);
        var result=run(root,"log",argument);
        assertEquals(2,result.code());
        assertTrue(result.out().isEmpty());
        assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @Test void outsideAndNestedDirectoriesDoNotSearchForParentRepositories() throws Exception {
        initialize();
        Path nested=Files.createDirectory(root.resolve("nested"));
        for(Path cwd:List.of(temporary,nested)) {
            var result=run(cwd,"log");
            assertEquals(1,result.code());
            assertTrue(result.out().isEmpty());
            assertTrue(result.err().contains("Not a VRN repository"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"missing-parent","cycle","bad-message","missing-head","pending"})
    void failedValidationPrintsNoPartialNormalHistory(String fault) throws Exception {
        initialize();
        snapshot("root","NONE",1,"root"); snapshot("head","root",2,"head");
        Files.writeString(storage.resolve("HEAD"),"head");
        if(fault.equals("missing-parent")) Files.delete(storage.resolve("snapshots/root.snapshot"));
        if(fault.equals("cycle")) snapshot("root","head",1,"cycle");
        if(fault.equals("bad-message")) Files.writeString(storage.resolve("snapshots/root.snapshot"),"VRN-SNAPSHOT\t99\n");
        if(fault.equals("missing-head")) Files.delete(storage.resolve("HEAD"));
        if(fault.equals("pending")) Files.writeString(storage.resolve("staging/commit.pending"),"pending remains untouched");
        var before=RepositoryPreflightTest.tree(root);
        var result=run(root,"log");
        assertEquals(1,result.code());
        assertEquals("",result.out());
        assertTrue(result.err().contains("Failed to read history"));
        assertFalse(result.err().contains(root.toString()));
        assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @Test void legacyMetadataAndEmptyMessagesRemainReadable() throws Exception {
        initialize();
        Files.writeString(storage.resolve("snapshots/legacy.snapshot"),"id=legacy\nparent=NONE\ntimestamp=0\nmessage=legacy unicode café\n[files]\nfile.txt=opaque\n");
        snapshot("head","legacy",1,"");
        Files.writeString(storage.resolve("HEAD"),"head");
        var result=run(root,"log");
        assertEquals(0,result.code(),result.err());
        assertTrue(result.out().contains("Message: \"\""));
        assertTrue(result.out().contains("legacy unicode café"));
    }

    @Test void longAncestryUsesNoRecursiveStackUnderConstrainedHeap() throws Exception {
        initialize();
        String parent="NONE";
        for(int i=0;i<1500;i++) { String id="s"+i; snapshot(id,parent,i,"entry "+i); parent=id; }
        Files.writeString(storage.resolve("HEAD"),parent);
        var result=run(root,"log");
        assertEquals(0,result.code(),result.err());
        assertTrue(result.out().startsWith("commit s1499\n"));
        assertEquals(1500,result.out().lines().filter(line->line.startsWith("commit ")).count());
    }

    @ParameterizedTest @ValueSource(strings={"file-case","dir-case","file-dir","dir-file"})
    void historiesOfStructuralTransitionsRemainReadable(String kind) throws Exception {
        root=Files.createDirectory(temporary.resolve("project"));
        var fixture=new PathTransitionTest(); fixture.root=root; fixture.prepare(kind); storage=fixture.storage;
        assertEquals(0,run(root,"add",fixture.selection(kind)).code());
        // Bypass Windows native launcher argument decoding so this tests the recorded
        // Unicode message, rather than the machine's active ANSI code page.
        new CommitService(storage).commit("Transition 日本語 "+kind);
        String head=Files.readString(storage.resolve("HEAD"));
        var before=RepositoryPreflightTest.tree(root);
        var result=run(root,"log");
        assertEquals(0,result.code(),result.err());
        assertTrue(result.out().startsWith("commit "+head+"\nParent: "+fixture.parent+"\n"));
        assertTrue(result.out().contains("Transition 日本語 "+kind),result.out());
        assertEquals(2,result.out().lines().filter(line->line.startsWith("commit ")).count());
        assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @Test void historyDoesNotConsumeLargeHistoricalObjects() throws Exception {
        initialize();
        Path source=root.resolve("large.bin");
        StreamingCaptureCliTest.generate(source,80L*1024*1024,309);
        assertEquals(0,run(root,"add","large.bin").code());
        assertEquals(0,run(root,"commit","-m","large streamed snapshot").code());
        String head=Files.readString(storage.resolve("HEAD"));
        String hash=new SnapshotStore(storage).load(head).files().get("large.bin");
        Files.delete(storage.resolve("objects/"+hash)); // History metadata inspection must not hash missing content.
        Files.delete(source);
        var before=RepositoryPreflightTest.tree(root);
        var result=run(root,"log");
        assertEquals(0,result.code(),result.err());
        assertTrue(result.out().contains(head));
        assertEquals(before,RepositoryPreflightTest.tree(root));
    }
    record Run(int code,String out,String err) {}
}
