package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RestoreCliTest {
    @TempDir Path temporary;
    SnapshotLogCliTest cli;
    SnapshotInspectionTest fixture;
    void initialize() throws Exception {
        empty();
        fixture.history(Map.of("file.txt",fixture.object("old")),Map.of("file.txt",fixture.object("current")));
        fixture.write("file.txt","current");
    }
    void empty() throws Exception {
        cli=new SnapshotLogCliTest(); cli.temporary=temporary; cli.initialize();
        fixture=new SnapshotInspectionTest(); fixture.root=cli.root; fixture.storage=cli.storage;
    }
    @Test void previewIsStrictlyReadOnly() throws Exception {
        initialize(); Files.delete(cli.storage.resolve("mutation.lock")); var before=RepositoryPreflightTest.tree(cli.root);
        var result=cli.run(cli.root,"restore","--plan","old");
        assertEquals(0,result.code(),result.err()); assertTrue(result.out().contains("REPLACE SAFE"),result.out());
        assertEquals(before,RepositoryPreflightTest.tree(cli.root));
    }
    @Test void restoringCurrentHeadIsANoOp() throws Exception {
        initialize(); var before=RepositoryPreflightTest.tree(cli.root);
        var result=cli.run(cli.root,"restore","HEAD");
        assertEquals(0,result.code(),result.err()); assertEquals(before,RepositoryPreflightTest.tree(cli.root));
    }
    @Test void olderContentsAreRecoveredWithoutMovingHeadOrStaging() throws Exception {
        initialize(); byte[] head=Files.readAllBytes(cli.storage.resolve("HEAD"));
        var history=RepositoryPreflightTest.tree(cli.storage);
        var result=cli.run(cli.root,"restore","old");
        assertEquals(0,result.code(),result.err()); assertEquals("old",Files.readString(cli.root.resolve("file.txt")));
        assertArrayEquals(head,Files.readAllBytes(cli.storage.resolve("HEAD")));
        assertEquals(history,RepositoryPreflightTest.tree(cli.storage));
    }

    @ParameterizedTest @ValueSource(strings={"missing","extra","force","overwrite","hard","discard","mixed","recover-target"})
    void unsupportedArgumentCombinationsNeverMutate(String kind) throws Exception {
        initialize(); var args=new ArrayList<String>(List.of("restore"));
        switch(kind) {
            case "missing" -> { }
            case "extra" -> args.addAll(List.of("old","extra"));
            case "force","overwrite","hard" -> args.addAll(List.of("old","--"+kind));
            case "discard" -> args.addAll(List.of("old","--discard-changes"));
            case "mixed" -> args.addAll(List.of("--plan","--recover"));
            case "recover-target" -> args.addAll(List.of("--recover","HEAD"));
        }
        var before=RepositoryPreflightTest.tree(cli.root); var result=cli.run(cli.root,args.toArray(String[]::new));
        assertEquals(2,result.code(),result.err()); assertEquals(before,RepositoryPreflightTest.tree(cli.root));
    }

    @Test void modifiedTrackedFileRefusesWithNoPartialExecution() throws Exception {
        initialize(); Files.writeString(cli.root.resolve("file.txt"),"local work"); var before=RepositoryPreflightTest.tree(cli.root);
        var preview=cli.run(cli.root,"restore","--plan","old"); assertEquals(1,preview.code()); assertTrue(preview.out().contains("CONFLICT"));
        var result=cli.run(cli.root,"restore","old"); assertEquals(1,result.code()); assertEquals("",result.out()); assertTrue(result.err().contains("file.txt"));
        assertEquals(before,RepositoryPreflightTest.tree(cli.root));
    }

    @ParameterizedTest @ValueSource(strings={"halt","halt-prepared","halt-complete","fail"})
    void packagedProcessFailureAndRecoveryPreserveTheDeclaredState(String mode) throws Exception {
        initialize(); var before=RepositoryPreflightTest.tree(cli.root);
        var crashed=runProbe(mode,"old",1); assertEquals(mode.startsWith("halt")?77:1,crashed.code(),crashed.err());
        if(mode.startsWith("halt")) {
            assertTrue(Files.exists(cli.storage.resolve("restore.pending")));
            for(String command:List.of("status","log")) assertEquals(1,cli.run(cli.root,command).code());
            assertEquals(1,cli.run(cli.root,"inspect","HEAD").code());
            var pending=RepositoryPreflightTest.tree(cli.root);
            assertEquals(1,cli.run(cli.root,"restore","--plan","old").code()); assertEquals(pending,RepositoryPreflightTest.tree(cli.root));
            var recovered=cli.run(cli.root,"restore","--recover"); assertEquals(0,recovered.code(),recovered.err());
            assertEquals(0,cli.run(cli.root,"restore","--recover").code());
        }
        if(mode.equals("halt-complete")) assertEquals("old",Files.readString(cli.root.resolve("file.txt")));
        else assertEquals(before,RepositoryPreflightTest.tree(cli.root));
        assertEquals("current",Files.readString(cli.storage.resolve("HEAD"))); assertFalse(Files.exists(cli.storage.resolve("restore.pending")));
    }

    SnapshotLogCliTest.Run runProbe(String mode,String selector,int step) throws Exception {
        String cp=Path.of(RestoreCrashProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())+java.io.File.pathSeparator+System.getProperty("vrn.executableJar");
        return process(List.of("-Xmx64m","-cp",cp,RestoreCrashProbe.class.getName(),cli.root.toString(),mode,selector,Integer.toString(step)));
    }
    SnapshotLogCliTest.Run process(List<String> arguments) throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString())); command.addAll(arguments);
        Path out=Files.createTempFile(temporary,"restore-out-",".txt"),err=Files.createTempFile(temporary,"restore-err-",".txt");
        Process process=new ProcessBuilder(command).directory(cli.root.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try { assertTrue(process.waitFor(300,TimeUnit.SECONDS),"Restore process timed out"); return new SnapshotLogCliTest.Run(process.exitValue(),Files.readString(out),Files.readString(err)); }
        finally { if(process.isAlive()) process.destroyForcibly(); }
    }

    @Test void completeHistoryRestorationIncludesStructuralChangesAndAForwardCommit() throws Exception {
        empty(); fixture.write("Main","initial"); fixture.write("config","flat"); fixture.write("obsolete","old");
        assertEquals(0,cli.run(cli.root,"add",".").code()); assertEquals(0,cli.run(cli.root,"commit","-m","Initial").code()); String first=Files.readString(cli.storage.resolve("HEAD"));
        fixture.write("Main","middle"); fixture.write("added","new"); assertEquals(0,cli.run(cli.root,"stage-delete","obsolete").code()); Files.delete(cli.root.resolve("obsolete"));
        assertEquals(0,cli.run(cli.root,"add",".").code()); assertEquals(0,cli.run(cli.root,"commit","-m","Middle").code());
        Files.move(cli.root.resolve("Main"),cli.root.resolve("spelling-temp")); Files.move(cli.root.resolve("spelling-temp"),cli.root.resolve("MAIN"));
        Files.delete(cli.root.resolve("config")); fixture.write("config/settings","nested");
        assertEquals(0,cli.run(cli.root,"add",".").code()); assertEquals(0,cli.run(cli.root,"commit","-m","Current").code()); String current=Files.readString(cli.storage.resolve("HEAD"));
        var metadata=RepositoryPreflightTest.tree(cli.storage);
        var preview=cli.run(cli.root,"restore","--plan",first); assertEquals(0,preview.code(),preview.err());
        var result=cli.run(cli.root,"restore",first); assertEquals(0,result.code(),result.err());
        assertEquals("initial",Files.readString(cli.root.resolve("Main"))); assertEquals("flat",Files.readString(cli.root.resolve("config"))); assertEquals("old",Files.readString(cli.root.resolve("obsolete")));
        assertFalse(Files.exists(cli.root.resolve("added"))); assertEquals(metadata,RepositoryPreflightTest.tree(cli.storage));
        assertEquals(0,cli.run(cli.root,"status").code());
        assertEquals(0,cli.run(cli.root,"stage-delete","added").code()); assertEquals(0,cli.run(cli.root,"add",".").code());
        var committed=cli.run(cli.root,"commit","-m","Commit restored contents"); assertEquals(0,committed.code(),committed.err());
        assertEquals(current,new SnapshotStore(cli.storage).load(Files.readString(cli.storage.resolve("HEAD"))).parentId());
    }

    @Test void multipleFilesLargerThanHeapCanBeBackedUpRestoredAndVerified() throws Exception {
        empty(); Map<String,String> expected=new java.util.LinkedHashMap<>();
        for(var entry:Map.of("large-a.bin",128L,"large-b.bin",80L).entrySet()) {
            StreamingCaptureCliTest.generate(cli.root.resolve(entry.getKey()),entry.getValue()*1024*1024,entry.getKey().hashCode()); expected.put(entry.getKey(),new FileHasher().hash(cli.root.resolve(entry.getKey())));
        }
        assertEquals(0,cli.run(cli.root,"add",".").code()); assertEquals(0,cli.run(cli.root,"commit","-m","Historical large files").code()); String target=Files.readString(cli.storage.resolve("HEAD"));
        StreamingCaptureCliTest.generate(cli.root.resolve("large-a.bin"),129L*1024*1024,901); StreamingCaptureCliTest.generate(cli.root.resolve("large-b.bin"),81L*1024*1024,902);
        assertEquals(0,cli.run(cli.root,"add",".").code()); assertEquals(0,cli.run(cli.root,"commit","-m","Current large files").code());
        var metadata=RestorePlanProbe.fingerprint(cli.storage);
        var restored=process(List.of("-Xmx64m","-jar",System.getProperty("vrn.executableJar"),"restore",target));
        assertEquals(0,restored.code(),restored.err());
        for(var entry:expected.entrySet()) assertEquals(entry.getValue(),new FileHasher().hash(cli.root.resolve(entry.getKey())));
        assertEquals(metadata,RestorePlanProbe.fingerprint(cli.storage)); assertFalse(Files.exists(cli.storage.resolve("restore.pending")));
    }
}
