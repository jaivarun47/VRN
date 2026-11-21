package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static com.vrn.core.RestoreFileOps.Event.*;
import static org.junit.jupiter.api.Assertions.*;

class RestoreStructureRecoveryTest {
    @TempDir Path temporary;
    static final class Crash extends Error {}
    RestoreExecutionTest fixture(Path root,String kind) throws Exception {
        Files.createDirectory(root); var f=new RestoreExecutionTest(); f.root=root; f.initialize();
        String target=switch(kind) { case "directory-case" -> "src/N/Main"; case "file-dir" -> "config/sub/file"; case "dir-file" -> "assets"; default -> "tree/N/file"; };
        String current=switch(kind) { case "directory-case" -> "SRC/n/Main"; case "file-dir" -> "config"; case "dir-file" -> "assets/icons/logo"; default -> "TREE/n"; };
        f.history(Map.of(target,f.object("old")),Map.of(current,f.object("head"))); f.write(current,"head"); return f;
    }

    @ParameterizedTest @ValueSource(strings={"directory-case","file-dir","dir-file","mixed"})
    void everyStructuralBoundarySurvivesAProcessInterruption(String kind) throws Exception {
        Path probe=temporary.resolve("catalog"); var f=fixture(probe,kind);
        var prepareCrash=new RestoreFileOps(probe) { @Override protected void checkpoint(Event e,String detail) { if(e==JOURNAL_WRITE && detail.equals("APPLYING:0:false")) throw new Crash(); } };
        assertThrows(Crash.class,()->new RestoreService(probe,prepareCrash).restore("old"));
        int count=RestoreJournal.decode(new MetadataPaths(f.storage).readLines("restore.pending")).steps().size(); new RestoreService(probe).recover();
        for(int boundary=1;boundary<=count;boundary++) {
            Path root=temporary.resolve("step-"+boundary); fixture(root,kind); var before=RepositoryPreflightTest.tree(root);
            int crashAt=boundary; AtomicInteger steps=new AtomicInteger();
            var ops=new RestoreFileOps(root) { @Override protected void checkpoint(Event e,String detail) { if(e==AFTER_STEP && steps.incrementAndGet()==crashAt) throw new Crash(); } };
            assertThrows(Crash.class,()->new RestoreService(root,ops).restore("old")); new RestoreService(root).recover();
            assertEquals(before,RepositoryPreflightTest.tree(root),kind+" step "+boundary);
            assertEquals("No pending restoration",new RestoreService(root).recover());
        }
    }

    @ParameterizedTest @CsvSource({"directory-case,DETACH_DIRECTORY","directory-case,ATTACH_DIRECTORY","file-dir,ATTACH_DIRECTORY","dir-file,DETACH_DIRECTORY","mixed,DETACH_DIRECTORY","mixed,ATTACH_DIRECTORY"})
    void directoryMutationFailuresRollBackSafely(String kind,String operation) throws Exception {
        Path root=temporary.resolve("project"); fixture(root,kind); var before=RepositoryPreflightTest.tree(root); AtomicBoolean failed=new AtomicBoolean();
        var ops=new RestoreFileOps(root) { @Override protected void checkpoint(Event e,String detail) throws IOException { if(e==AFTER_STEP && detail.startsWith(operation+":") && failed.compareAndSet(false,true)) throw new IOException("directory fault"); } };
        var failure=assertThrows(RestoreFailure.class,()->new RestoreService(root,ops).restore("old")); assertTrue(failed.get());
        assertEquals(RestoreFailure.Status.ROLLED_BACK,failure.status()); assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @ParameterizedTest @ValueSource(strings={"file-publication","directory-publication","source-changed"})
    void destinationRacesNeverReplaceUnexpectedFiles(String race) throws Exception {
        Path root=temporary.resolve("project"); var f=fixture(root,"file-dir"); AtomicBoolean once=new AtomicBoolean();
        var ops=new RestoreFileOps(root) {
            @Override void link(Path destination,Path source) throws IOException {
                if(race.equals("file-publication") && destination.equals(root.resolve("config/sub/file")) && once.compareAndSet(false,true)) Files.writeString(destination,"external work");
                super.link(destination,source);
            }
            @Override void rename(Path source,Path target) throws IOException {
                if(race.equals("directory-publication") && target.equals(root.resolve("config")) && once.compareAndSet(false,true)) Files.writeString(target,"external work");
                if(race.equals("source-changed") && source.equals(root.resolve("config")) && once.compareAndSet(false,true)) Files.writeString(source,"external work");
                super.rename(source,target);
            }
        };
        var failure=assertThrows(RestoreFailure.class,()->new RestoreService(root,ops).restore("old"));
        assertEquals(RestoreFailure.Status.RECOVERY_REQUIRED,failure.status()); assertTrue(once.get());
        if(race.equals("file-publication")) assertEquals("external work",Files.readString(root.resolve("config/sub/file")));
        if(race.equals("directory-publication")) assertEquals("external work",Files.readString(root.resolve("config")));
        if(race.equals("source-changed")) assertEquals("external work",Files.readString(f.storage.resolve("restore-work/original-files/0")));
        assertEquals("head",Files.readString(f.storage.resolve("restore-work/backup/0")));
    }

    @Test void directoryRollbackCanBeInterruptedAndResumed() throws Exception {
        Path root=temporary.resolve("project"); fixture(root,"directory-case"); var before=RepositoryPreflightTest.tree(root); AtomicBoolean failed=new AtomicBoolean(),crashed=new AtomicBoolean();
        var ops=new RestoreFileOps(root) { @Override protected void checkpoint(Event e,String detail) throws IOException {
            if(e==VERIFY_RESULT && failed.compareAndSet(false,true)) throw new IOException("force rollback");
            if(e==AFTER_ROLLBACK_STEP && detail.startsWith("ATTACH_DIRECTORY:") && crashed.compareAndSet(false,true)) throw new Crash();
        } };
        assertThrows(Crash.class,()->new RestoreService(root,ops).restore("old")); new RestoreService(root).recover(); assertEquals(before,RepositoryPreflightTest.tree(root));
    }
}
