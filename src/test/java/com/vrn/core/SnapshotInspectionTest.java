package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotInspectionTest {
    @TempDir Path root;
    Path storage;
    @BeforeEach void initialize() throws Exception {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
    }
    String object(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        new ObjectStore(storage).store(id, bytes);
        return id;
    }
    void snapshot(String id, String parent, Map<String,String> files) throws Exception {
        Files.writeString(storage.resolve("snapshots/" + id + ".snapshot"), MetadataCodec.encodeSnapshot(new Snapshot(id,parent,0,"日本語\nmessage",files)));
    }
    void history(Map<String,String> old, Map<String,String> current) throws Exception {
        snapshot("old", "NONE", old); snapshot("current", "old", current);
        Files.writeString(storage.resolve("HEAD"), "current");
    }
    Path write(String path, String text) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent()); Files.writeString(file, text); return file;
    }
    void unchangedFailure(String selector) throws Exception {
        Files.deleteIfExists(storage.resolve("mutation.lock"));
        var before = RepositoryPreflightTest.tree(root);
        assertThrows(Exception.class, () -> new SnapshotInspector(storage).inspect(selector));
        assertThrows(Exception.class, () -> new RestorePlanner(root).plan(selector));
        assertEquals(before, RepositoryPreflightTest.tree(root));
    }

    @Test void exactSelectionHeadMetadataAndImmutableSortedMappings() throws Exception {
        String hash = object("bytes");
        history(Map.of("src/日本語.java",hash,"README.md",hash),Map.of());
        var before = RepositoryPreflightTest.tree(root);
        var old = new SnapshotInspector(storage).inspect("old");
        assertEquals("old", old.id()); assertEquals("NONE", old.parentId()); assertEquals(0, old.timestamp());
        assertEquals("日本語\nmessage",old.message());
        assertEquals(List.of("README.md","src/日本語.java"),new ArrayList<>(old.files().keySet()));
        assertEquals(hash,old.files().get("src/日本語.java"));
        assertThrows(UnsupportedOperationException.class,()->old.files().clear());
        assertEquals("current",new SnapshotInspector(storage).inspect("HEAD").id());
        assertTrue(new SnapshotInspector(storage).inspect("current").files().isEmpty());
        assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @ParameterizedTest @ValueSource(strings={"HEAD","unknown","NONE","../escape","/absolute","bad id","cur","head"})
    void invalidOrEmptySelectorsFailWithoutWrites(String selector) throws Exception { unchangedFailure(selector); }

    @ParameterizedTest @ValueSource(strings={"orphan","missing-parent","cycle","self","version","mismatch","protected","descendant","case-alias","bad-parent"})
    void invalidAncestryCannotAuthorizeSelection(String fault) throws Exception {
        history(Map.of(),Map.of());
        String selector="current";
        switch(fault) {
            case "orphan" -> { snapshot("orphan","NONE",Map.of()); selector="orphan"; }
            case "missing-parent" -> Files.delete(storage.resolve("snapshots/old.snapshot"));
            case "cycle" -> snapshot("old","current",Map.of());
            case "self" -> snapshot("old","old",Map.of());
            case "version" -> Files.writeString(storage.resolve("snapshots/old.snapshot"),"VRN-SNAPSHOT\t99\n");
            case "mismatch" -> Files.writeString(storage.resolve("snapshots/old.snapshot"),MetadataCodec.encodeSnapshot(new Snapshot("other","NONE",0,"x",Map.of())));
            case "protected" -> snapshot("old","NONE",Map.of(".vrnauto/data","opaque"));
            case "descendant" -> snapshot("old","NONE",Map.of("node","opaque","node/child","opaque"));
            case "case-alias" -> snapshot("old","NONE",Map.of("a","opaque","A","opaque"));
            case "bad-parent" -> {
                String text=Files.readString(storage.resolve("snapshots/old.snapshot")).replace("parent\tNONE","parent\t../escape");
                Files.writeString(storage.resolve("snapshots/old.snapshot"),text);
            }
        }
        unchangedFailure(selector);
    }

    @ParameterizedTest @ValueSource(strings={"missing","digest","directory","malformed-reference","unreadable"})
    void objectFailuresPreventInspectionAndPlanning(String fault) throws Exception {
        String hash=object("valid bytes"); history(Map.of("file.txt",hash),Map.of());
        Path path=storage.resolve("objects/"+hash);
        switch(fault) {
            case "missing" -> Files.delete(path);
            case "digest" -> Files.writeString(path,"corrupt bytes");
            case "directory" -> { Files.delete(path); Files.createDirectory(path); }
            case "malformed-reference" -> snapshot("old","NONE",Map.of("file.txt","opaque"));
            case "unreadable" -> {
                var denied=new ObjectStore(storage) {
                    @Override protected SeekableByteChannel openObject(Path file) throws IOException { throw new AccessDeniedException(file.toString()); }
                };
                var before=RepositoryPreflightTest.tree(root);
                Exception failure=assertThrows(IOException.class,()->new SnapshotInspector(storage,denied).inspect("old"));
                assertTrue(failure.getMessage().contains("file.txt"));
                assertThrows(IOException.class,()->new RestorePlanner(root,new SnapshotInspector(storage,denied),new FileHasher()).plan("old"));
                assertEquals(before,RepositoryPreflightTest.tree(root)); return;
            }
        }
        unchangedFailure("old");
    }

    @Test void objectJunctionIsRejectedWithoutTouchingExternalData() throws Exception {
        String hash=object("bytes"); history(Map.of("file.txt",hash),Map.of());
        Path path=storage.resolve("objects/"+hash); Files.delete(path);
        Path outside=Files.createDirectory(root.resolveSibling(root.getFileName()+"-object-outside"));
        Files.writeString(outside.resolve("sentinel"),"preserve");
        WindowsMetadataBoundaryTest.junction(path,outside);
        try { unchangedFailure("old"); assertEquals("preserve",Files.readString(outside.resolve("sentinel"))); }
        finally { Files.delete(path); Files.delete(outside.resolve("sentinel")); Files.delete(outside); }
    }

    @ParameterizedTest @ValueSource(strings={"pending","malformed-head","missing-head","missing-active"})
    void invalidRepositoryStateIsNeverRepaired(String fault) throws Exception {
        history(Map.of(),Map.of());
        switch(fault) {
            case "pending" -> Files.writeString(storage.resolve("staging/commit.pending"),"pending remains untouched");
            case "malformed-head" -> Files.writeString(storage.resolve("HEAD"),"bad id");
            case "missing-head" -> Files.delete(storage.resolve("HEAD"));
            case "missing-active" -> Files.delete(storage.resolve("snapshots/current.snapshot"));
        }
        unchangedFailure("old");
    }

    @Test void sharedObjectsAreVerifiedOncePerInspection() throws Exception {
        String hash=object("shared"); history(Map.of("a",hash,"b",hash),Map.of());
        var reads=new ArrayList<String>();
        var counting=new ObjectStore(storage) { @Override public void verify(String id) throws IOException { reads.add(id); super.verify(id); } };
        new SnapshotInspector(storage,counting).inspect("old");
        assertEquals(List.of(hash),reads);
    }

    @Test void fullIdIsAnExactMatchRatherThanAPrefixOrCaseFold() throws Exception {
        history(Map.of(),Map.of());
        for(String selector:List.of("cur","CURRENT","unknown")) unchangedFailure(selector);
        assertEquals("current",new SnapshotResolver(storage).resolve("current").target().id());
    }

    @Test void pendingRecordAppearingDuringVerificationIsNotRecovered() throws Exception {
        String hash=object("bytes"); history(Map.of("file",hash),Map.of());
        var changing=new ObjectStore(storage) {
            @Override public void verify(String id) throws IOException {
                super.verify(id); Files.writeString(storage.resolve("staging/commit.pending"),"external pending record");
            }
        };
        assertThrows(IOException.class,()->new SnapshotInspector(storage,changing).inspect("old"));
        assertEquals("external pending record",Files.readString(storage.resolve("staging/commit.pending")));
    }

    @Test void headSelectorIsNotShadowedByAHistoricalIdentifierNamedHead() throws Exception {
        snapshot("HEAD","NONE",Map.of()); snapshot("current","HEAD",Map.of()); Files.writeString(storage.resolve("HEAD"),"current");
        assertEquals("current",new SnapshotResolver(storage).resolve("HEAD").target().id());
    }
}
