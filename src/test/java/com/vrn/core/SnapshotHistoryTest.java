package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotHistoryTest {
    @TempDir Path root;
    Path storage;
    @BeforeEach void initialize() throws Exception {
        new RepositoryInitializer().initialize(root);
        storage=root.resolve(".vrn");
    }
    void save(String id,String parent,long time,String message,Map<String,String> files) throws Exception {
        Files.writeString(storage.resolve("snapshots/"+id+".snapshot"),MetadataCodec.encodeSnapshot(new Snapshot(id,parent,time,message,files)));
    }
    void chain() throws Exception {
        save("root","NONE",2000,"root message",Map.of());
        save("head","root",1000,"new message",Map.of());
        Files.writeString(storage.resolve("HEAD"),"head");
    }
    void unchangedFailure() throws Exception {
        var before=RepositoryPreflightTest.tree(root);
        assertThrows(Exception.class,()->new SnapshotHistory(storage).read());
        assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @Test void orderUsesParentsAndRecordsDoNotRequireObjectsOrRegistry() throws Exception {
        save("older","NONE",9000,"",Map.of("file.txt","opaque_reference"));
        save("newer","older",1000,"日本語\nmessage",Map.of());
        save("unreachable","NONE",Long.MAX_VALUE,"not history",Map.of());
        Files.writeString(storage.resolve("HEAD"),"newer");
        Files.writeString(storage.resolve("registry"),"unrecognized advisory data must not drive history");
        Files.deleteIfExists(storage.resolve("mutation.lock"));
        var before=RepositoryPreflightTest.tree(root);
        var entries=new SnapshotHistory(storage).read();
        assertEquals(List.of("newer","older"),entries.stream().map(SnapshotHistory.Entry::id).toList());
        assertEquals("older",entries.getFirst().parentId());
        assertEquals("NONE",entries.getLast().parentId());
        assertEquals("",entries.getLast().message());
        assertEquals(1000,entries.getFirst().timestamp());
        assertThrows(UnsupportedOperationException.class,entries::clear);
        assertEquals(before,RepositoryPreflightTest.tree(root));
        assertFalse(Files.exists(storage.resolve("mutation.lock")));
    }

    @ParameterizedTest @ValueSource(strings={"header","type","base64","utf8","id","parent","empty-parent","root-marker","negative-time","overflow-time","duplicate","path","descendant","case-alias","count"})
    void malformedReachableMetadataFailsWithoutWrites(String fault) throws Exception {
        chain();
        String good=Files.readString(storage.resolve("snapshots/head.snapshot"));
        String corrupt=switch(fault) {
            case "header" -> good.replace("VRN-SNAPSHOT\t1","VRN-SNAPSHOT\t99");
            case "type" -> good.replace("VRN-SNAPSHOT","VRN-STAGING");
            case "base64" -> good.replace("message\tbmV3IG1lc3NhZ2U=","message\t%%%");
            case "utf8" -> good.replace("message\tbmV3IG1lc3NhZ2U=","message\t/w==");
            case "id" -> good.replace("id\thead","id\twrong");
            case "parent" -> good.replace("parent\troot","parent\t../escape");
            case "empty-parent" -> good.replace("parent\troot","parent\t");
            case "root-marker" -> good.replace("parent\troot","parent\tnone");
            case "negative-time" -> good.replace("timestamp\t1000","timestamp\t-1");
            case "overflow-time" -> good.replace("timestamp\t1000","timestamp\t999999999999999999999999");
            case "duplicate" -> good.replace("files\t0","message\tZHVwbGljYXRl\nfiles\t0");
            case "path" -> MetadataCodec.encodeSnapshot(new Snapshot("head","root",1,"x",Map.of(".vrn/HEAD","ref")));
            case "descendant" -> MetadataCodec.encodeSnapshot(new Snapshot("head","root",1,"x",Map.of("node","ref","node/child","ref")));
            case "case-alias" -> MetadataCodec.encodeSnapshot(new Snapshot("head","root",1,"x",Map.of("a.txt","ref","A.TXT","ref")));
            default -> good.replace("files\t0","files\t1");
        };
        assertNotEquals(good,corrupt);
        Files.writeString(storage.resolve("snapshots/head.snapshot"),corrupt);
        unchangedFailure();
    }

    @ParameterizedTest @ValueSource(strings={"missing-head","missing-parent","self","cycle"})
    void brokenAncestryIsRejected(String fault) throws Exception {
        chain();
        if(fault.equals("missing-head")) Files.delete(storage.resolve("snapshots/head.snapshot"));
        if(fault.equals("missing-parent")) Files.delete(storage.resolve("snapshots/root.snapshot"));
        if(fault.equals("self")) save("head","head",1,"self",Map.of());
        if(fault.equals("cycle")) save("root","head",1,"cycle",Map.of());
        var before=RepositoryPreflightTest.tree(root);
        Exception failure=assertThrows(Exception.class,()->new SnapshotHistory(storage).read());
        if(fault.equals("self")||fault.equals("cycle")) assertTrue(failure.getMessage().contains("cycle"));
        if(fault.equals("missing-parent")) assertTrue(failure.getMessage().contains("parent of 'head'"));
        assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @ParameterizedTest @ValueSource(strings={"", "NONE\nextra", "../outside", "bad id"})
    void invalidHeadIsNotEmptyHistory(String text) throws Exception {
        Files.writeString(storage.resolve("HEAD"),text);
        unchangedFailure();
    }

    @Test void missingHeadIsNotEmptyHistory() throws Exception {
        Files.delete(storage.resolve("HEAD"));
        unchangedFailure();
    }

    @ParameterizedTest @ValueSource(strings={"published","unpublished","malformed"})
    void pendingRecoveryIsRefusedWithoutEvenDeletingConsumedIndex(String state) throws Exception {
        save("head","NONE",1,"head",Map.of());
        save("next","head",2,"next",Map.of());
        Files.writeString(storage.resolve("HEAD"),state.equals("published")?"next":"head");
        Files.writeString(storage.resolve("staging/index"),MetadataCodec.encodeOperations(Map.of()));
        try(var guard=RepositoryMutationGuard.acquire(storage)) { new CommitRecovery(storage,new AtomicFilePublisher()).prepare("next","head"); }
        if(state.equals("malformed")) Files.writeString(storage.resolve("staging/commit.pending"),"bad recovery record");
        var before=RepositoryPreflightTest.tree(root);
        Exception failure=assertThrows(IOException.class,()->new SnapshotHistory(storage).read());
        assertTrue(failure.getMessage().contains("Pending commit recovery"));
        assertEquals(before,RepositoryPreflightTest.tree(root));
        if(!state.equals("malformed")) {
            new StagingArea(storage).recover(); // Explicit separate mutation, never part of history reading.
            assertEquals(state.equals("published")?2:1,new SnapshotHistory(storage).read().size());
        }
    }

    @Test void writerGuardDoesNotPreventAnAnchoredObservationalRead() throws Exception {
        chain();
        try(var ignored=RepositoryMutationGuard.acquire(storage)) { /* Explicit writer fixture creates its lock first. */ }
        var before=RepositoryPreflightTest.tree(root);
        try(var guard=RepositoryMutationGuard.acquire(storage)) {
            assertEquals(2,new SnapshotHistory(storage).read().size());
        }
        assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @Test void cooperatingCommitDuringTraversalDoesNotChangeTheCapturedAncestry() throws Exception {
        chain();
        Files.writeString(root.resolve("new.txt"),"new selected content");
        new AddService(root).add(Path.of("new.txt"));
        AtomicBoolean published=new AtomicBoolean();
        SnapshotStore loader=new SnapshotStore(storage) {
            @Override Snapshot loadAnchored(String id) throws Exception {
                if(published.compareAndSet(false,true)) new CommitService(storage).commit("concurrent next generation");
                return super.loadAnchored(id);
            }
        };
        var entries=new SnapshotHistory(storage,loader).read();
        assertEquals(List.of("head","root"),entries.stream().map(SnapshotHistory.Entry::id).toList());
        assertNotEquals("head",Files.readString(storage.resolve("HEAD")));
        assertEquals("head",new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD"))).parentId());
    }

    @Test void externalParentRemovalFailsInsteadOfReturningATruncatedList() throws Exception {
        chain();
        SnapshotStore loader=new SnapshotStore(storage) {
            @Override Snapshot loadAnchored(String id) throws Exception {
                Snapshot snapshot=super.loadAnchored(id);
                if(id.equals("head")) Files.delete(storage.resolve("snapshots/root.snapshot"));
                return snapshot;
            }
        };
        Exception error=assertThrows(IOException.class,()->new SnapshotHistory(storage,loader).read());
        assertTrue(error.getMessage().contains("parent of 'head'"));
        assertEquals("head",Files.readString(storage.resolve("HEAD")));
        assertFalse(Files.exists(storage.resolve("snapshots/root.snapshot")));
    }

    @Test void recoveryAppearingDuringTraversalIsRefusedWithoutCleanup() throws Exception {
        chain();
        SnapshotStore loader=new SnapshotStore(storage) {
            @Override Snapshot loadAnchored(String id) throws Exception {
                Snapshot snapshot=super.loadAnchored(id);
                if(id.equals("head")) Files.writeString(storage.resolve("staging/commit.pending"),"external pending record");
                return snapshot;
            }
        };
        assertThrows(IOException.class,()->new SnapshotHistory(storage,loader).read());
        assertEquals("external pending record",Files.readString(storage.resolve("staging/commit.pending")));
    }

    @Test void malformedStagingIsNotBypassedForHistory() throws Exception {
        chain();
        Files.writeString(storage.resolve("staging/index"),"VRN-STAGING\t99\n");
        unchangedFailure();
    }

    @Test void unsafeParentMetadataJunctionIsRejected() throws Exception {
        chain();
        Files.delete(storage.resolve("snapshots/root.snapshot"));
        Path outside=Files.createDirectory(root.resolveSibling(root.getFileName()+"-outside-history"));
        Files.writeString(outside.resolve("data"),"unchanged outside");
        Path link=storage.resolve("snapshots/root.snapshot");
        WindowsMetadataBoundaryTest.junction(link,outside);
        try { assertThrows(Exception.class,()->new SnapshotHistory(storage).read()); assertEquals("unchanged outside",Files.readString(outside.resolve("data"))); }
        finally { Files.delete(link); Files.delete(outside.resolve("data")); Files.delete(outside); }
    }
}
