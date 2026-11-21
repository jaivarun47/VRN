package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static com.vrn.core.RestoreFileOps.Event.*;
import static org.junit.jupiter.api.Assertions.*;

class RestoreRecoveryTest {
    @TempDir Path root;
    RestoreExecutionTest fixture;
    Path storage;
    @BeforeEach void initialize() throws Exception {
        fixture=new RestoreExecutionTest(); fixture.root=root; fixture.initialize(); fixture.replacement(); storage=fixture.storage;
    }
    static final class Crash extends Error {}
    void pending() { assertTrue(Files.exists(storage.resolve("restore.pending"))); }
    void recovered() throws Exception {
        assertFalse(Files.exists(storage.resolve("restore.pending"))); assertFalse(Files.exists(storage.resolve("restore-work")));
        for(String path:List.of("Alpha","Beta","Gamma")) assertEquals("head",Files.readString(root.resolve(path)));
    }

    @ParameterizedTest @ValueSource(strings={"backup-copy","backup-verify","target-copy","target-verify","journal-prepare","journal-apply","journal-progress","first-publish","middle-publish","last-publish","after-detach","after-publish","verify","complete-record","rollback","cleanup","cleanup-middle","cleanup-marker","rollback-cleanup"})
    void ordinaryFailuresHaveHonestRollbackOrCleanupOutcomes(String fault) throws Exception {
        var before=RepositoryPreflightTest.tree(root); AtomicBoolean fired=new AtomicBoolean(),primary=new AtomicBoolean(); AtomicInteger publications=new AtomicInteger();
        var ops=new RestoreFileOps(root) {
            @Override protected void checkpoint(Event event,String detail) throws IOException {
                if((fault.equals("rollback")||fault.equals("rollback-cleanup")) && event==BEFORE_STEP && detail.equals("PUBLISH_FILE:Beta") && primary.compareAndSet(false,true)) throw new IOException("primary publication failure");
                boolean fail=switch(fault) {
                    case "backup-copy" -> event==BACKUP_COPY;
                    case "backup-verify" -> event==BACKUP_VERIFY;
                    case "target-copy" -> event==TARGET_PREPARE;
                    case "target-verify" -> event==TARGET_VERIFY;
                    case "journal-prepare" -> event==JOURNAL_WRITE && detail.startsWith("PREPARING");
                    case "journal-apply" -> event==JOURNAL_WRITE && detail.equals("APPLYING:0:false");
                    case "journal-progress" -> event==JOURNAL_WRITE && detail.equals("APPLYING:1:false");
                    case "first-publish","middle-publish","last-publish" -> event==BEFORE_STEP && detail.startsWith("PUBLISH_FILE:") && publications.incrementAndGet()==(fault.equals("first-publish")?1:fault.equals("middle-publish")?2:3);
                    case "after-detach" -> event==AFTER_STEP && detail.startsWith("DETACH_FILE:");
                    case "after-publish" -> event==AFTER_STEP && detail.startsWith("PUBLISH_FILE:");
                    case "verify" -> event==VERIFY_RESULT;
                    case "complete-record" -> event==JOURNAL_WRITE && detail.startsWith("COMPLETE:");
                    case "rollback" -> event==ROLLBACK_STEP;
                    case "cleanup" -> event==CLEANUP && detail.equals("COMPLETE");
                    case "cleanup-middle" -> event==CLEANUP && detail.equals("prepared/1");
                    case "cleanup-marker" -> event==CLEANUP && detail.equals("restore.pending");
                    case "rollback-cleanup" -> event==CLEANUP && detail.equals("ROLLED_BACK");
                    default -> false;
                };
                if(fail && fired.compareAndSet(false,true)) throw new IOException("injected "+fault);
            }
        };
        var failure=assertThrows(RestoreFailure.class,()->new RestoreService(root,ops).restore("old")); assertTrue(fired.get(),fault);
        if(fault.startsWith("cleanup")) {
            assertEquals(RestoreFailure.Status.COMPLETED_CLEANUP_PENDING,failure.status()); pending();
            for(String path:List.of("Alpha","Beta","Gamma")) assertEquals("old",Files.readString(root.resolve(path)));
            new RestoreService(root).recover(); fixture.noPending();
        } else {
            if(fault.equals("rollback")||fault.equals("rollback-cleanup")) { assertEquals(RestoreFailure.Status.RECOVERY_REQUIRED,failure.status()); pending(); new RestoreService(root).recover(); }
            recovered(); assertEquals(before,RepositoryPreflightTest.tree(root),fault);
        }
    }

    @ParameterizedTest @ValueSource(strings={"prepared","before-first","step1","step2","step3","step4","step5","step6","before-complete","after-complete"})
    void freshInstancesRecoverInterruptedOperationsIdempotently(String point) throws Exception {
        var before=RepositoryPreflightTest.tree(root); AtomicInteger steps=new AtomicInteger(); AtomicBoolean crashed=new AtomicBoolean();
        var ops=new RestoreFileOps(root) {
            @Override protected void checkpoint(Event event,String detail) {
                boolean crash=switch(point) {
                    case "prepared" -> event==JOURNAL_WRITE && detail.equals("APPLYING:0:false");
                    case "before-first" -> event==BEFORE_STEP;
                    case "before-complete" -> event==BEFORE_COMPLETE;
                    case "after-complete" -> event==CLEANUP && detail.equals("COMPLETE");
                    default -> event==AFTER_STEP && steps.incrementAndGet()==Integer.parseInt(point.substring(4));
                };
                if(crash && crashed.compareAndSet(false,true)) throw new Crash();
            }
        };
        assertThrows(Crash.class,()->new RestoreService(root,ops).restore("old")); pending();
        var pending=RepositoryPreflightTest.tree(root);
        assertThrows(Exception.class,()->new RestorePlanner(root).plan("old"));
        assertThrows(Exception.class,()->new SnapshotInspector(storage).inspect("old"));
        assertThrows(Exception.class,()->new SnapshotHistory(storage).read());
        assertThrows(Exception.class,()->new AddService(root).add(Path.of(".")));
        assertThrows(Exception.class,()->new CommitService(storage).commit("blocked"));
        assertThrows(Exception.class,()->WorkingTreeStatus.compute(root));
        assertThrows(Exception.class,()->new RepositoryInitializer().initialize(root));
        assertEquals(pending,RepositoryPreflightTest.tree(root));
        new RestoreService(root).recover(); fixture.noPending();
        if(point.equals("after-complete")) for(String path:List.of("Alpha","Beta","Gamma")) assertEquals("old",Files.readString(root.resolve(path)));
        else { recovered(); assertEquals(before,RepositoryPreflightTest.tree(root)); }
        var after=RepositoryPreflightTest.tree(root); assertEquals("No pending restoration",new RestoreService(root).recover()); assertEquals(after,RepositoryPreflightTest.tree(root));
    }

    @Test void interruptionDuringRollbackIsReconciledFromActualEvidence() throws Exception {
        AtomicBoolean fail=new AtomicBoolean(),crash=new AtomicBoolean(); var before=RepositoryPreflightTest.tree(root);
        var ops=new RestoreFileOps(root) {
            @Override protected void checkpoint(Event event,String detail) throws IOException {
                if(event==BEFORE_STEP && detail.equals("PUBLISH_FILE:Beta") && fail.compareAndSet(false,true)) throw new IOException("fail forward");
                if(event==AFTER_ROLLBACK_STEP && crash.compareAndSet(false,true)) throw new Crash();
            }
        };
        assertThrows(Crash.class,()->new RestoreService(root,ops).restore("old")); pending(); new RestoreService(root).recover(); recovered(); assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @ParameterizedTest @ValueSource(strings={"journal-digest","missing-backup","bad-backup","missing-journal","unexpected-asset","changed-head","changed-index","both-recoveries"})
    void invalidRecoveryEvidenceIsPreservedWithoutWorkingWrites(String fault) throws Exception {
        var ops=new RestoreFileOps(root) { @Override protected void checkpoint(Event e,String d) { if(e==AFTER_STEP) throw new Crash(); } };
        assertThrows(Crash.class,()->new RestoreService(root,ops).restore("old"));
        switch(fault) {
            case "journal-digest" -> Files.writeString(storage.resolve("restore.pending"),"VRN-RESTORE\t99\n");
            case "missing-backup" -> Files.delete(storage.resolve("restore-work/backup/0"));
            case "bad-backup" -> Files.writeString(storage.resolve("restore-work/backup/0"),"bad backup");
            case "missing-journal" -> Files.delete(storage.resolve("restore.pending"));
            case "unexpected-asset" -> Files.writeString(storage.resolve("restore-work/unexpected"),"preserve");
            case "changed-head" -> Files.writeString(storage.resolve("HEAD"),"old");
            case "changed-index" -> Files.writeString(storage.resolve("staging/index"),MetadataCodec.encodeOperations(Map.of()));
            case "both-recoveries" -> Files.writeString(storage.resolve("staging/commit.pending"),"other recovery remains untouched");
        }
        var before=RepositoryPreflightTest.tree(root);
        assertThrows(Exception.class,()->new RestoreService(root).recover()); assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @ParameterizedTest @ValueSource(strings={"different","same-bytes","edited-publication"})
    void externalWorkingChangesAreNeverOverwrittenByRollback(String change) throws Exception {
        AtomicBoolean once=new AtomicBoolean();
        var ops=new RestoreFileOps(root) {
            @Override protected void checkpoint(Event event,String detail) throws IOException {
                boolean trigger=change.equals("edited-publication")?event==AFTER_STEP && detail.equals("PUBLISH_FILE:Alpha"):event==BEFORE_STEP && detail.equals("PUBLISH_FILE:Alpha");
                if(trigger && once.compareAndSet(false,true)) { Files.writeString(root.resolve("Alpha"),change.equals("same-bytes")?"old":"external work"); throw new IOException("external interference"); }
            }
        };
        var failure=assertThrows(RestoreFailure.class,()->new RestoreService(root,ops).restore("old"));
        assertEquals(RestoreFailure.Status.RECOVERY_REQUIRED,failure.status()); pending();
        String external=Files.readString(root.resolve("Alpha")); var before=RepositoryPreflightTest.tree(root);
        assertThrows(Exception.class,()->new RestoreService(root).recover()); assertEquals(external,Files.readString(root.resolve("Alpha"))); assertEquals(before,RepositoryPreflightTest.tree(root));
        assertEquals("head",Files.readString(storage.resolve("restore-work/backup/0"))); assertEquals("head",Files.readString(storage.resolve("restore-work/original-files/0")));
    }

    @ParameterizedTest @ValueSource(strings={"after-detach","after-complete-journal"})
    void successfulFilesystemOperationWithAnExceptionIsReconciled(String point) throws Exception {
        AtomicBoolean once=new AtomicBoolean(); var before=RepositoryPreflightTest.tree(root);
        var ops=new RestoreFileOps(root) {
            @Override void rename(Path source,Path target) throws IOException {
                super.rename(source,target);
                if(point.equals("after-detach") && source.equals(root.resolve("Alpha")) && once.compareAndSet(false,true)) throw new IOException("ack lost after move");
            }
            @Override protected void move(Path source,Path target) throws IOException {
                super.move(source,target);
                if(point.equals("after-complete-journal") && target.getFileName().toString().equals("restore.pending")) {
                    try {
                        var journal=RestoreJournal.decode(new MetadataPaths(storage).readLines("restore.pending"));
                        if(journal.phase()==RestoreJournal.Phase.COMPLETE && once.compareAndSet(false,true)) throw new IOException("ack lost after completion");
                    } catch(IOException failure) { throw failure; }
                    catch(Exception failure) { throw new IOException(failure); }
                }
            }
        };
        var failure=assertThrows(RestoreFailure.class,()->new RestoreService(root,ops).restore("old"));
        if(point.equals("after-detach")) { assertEquals(RestoreFailure.Status.ROLLED_BACK,failure.status()); assertEquals(before,RepositoryPreflightTest.tree(root)); }
        else { assertEquals(RestoreFailure.Status.COMPLETED_CLEANUP_PENDING,failure.status()); new RestoreService(root).recover(); assertEquals("old",Files.readString(root.resolve("Alpha"))); }
    }

    @ParameterizedTest @ValueSource(strings={"backup","target"})
    void partialPreparationWritesCannotChangeWorkingFiles(String kind) throws Exception {
        var before=RepositoryPreflightTest.tree(root); AtomicInteger writes=new AtomicInteger();
        var ops=new RestoreFileOps(root) {
            @Override protected void writeCopy(java.nio.channels.FileChannel output,java.nio.ByteBuffer bytes) throws IOException {
                if(writes.incrementAndGet()==(kind.equals("backup")?1:4)) {
                    var partial=bytes.slice(); partial.limit(Math.max(1,partial.remaining()/2)); output.write(partial);
                    throw new IOException("No space left during "+kind+" preparation");
                }
                super.writeCopy(output,bytes);
            }
        };
        var failure=assertThrows(RestoreFailure.class,()->new RestoreService(root,ops).restore("old"));
        assertEquals(RestoreFailure.Status.PREPARATION_FAILED,failure.status()); assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @Test void emptyUnpublishedPreparationAreaCanBeRetiredExplicitly() throws Exception {
        var before=RepositoryPreflightTest.tree(root);
        Path area=Files.createDirectory(storage.resolve("restore-work"));
        for(String bucket:RestoreFileOps.BUCKETS) Files.createDirectory(area.resolve(bucket));
        assertThrows(Exception.class,()->new SnapshotHistory(storage).read());
        assertTrue(new RestoreService(root).recover().contains("unpublished")); assertEquals(before,RepositoryPreflightTest.tree(root));
    }
}
