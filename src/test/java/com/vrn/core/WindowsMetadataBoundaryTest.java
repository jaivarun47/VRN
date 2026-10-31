package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsMetadataBoundaryTest {
    @TempDir Path temp;
    Path root,storage;
    String hash;

    @BeforeEach void initialize() throws Exception {
        root=Files.createDirectory(temp.resolve("project"));
        new RepositoryInitializer().initialize(root);
        storage=root.resolve(".vrn");
        Files.writeString(root.resolve("file.txt"),"bytes");
        var content=new FileHasher().hashWithBytes(root.resolve("file.txt"));
        hash=content.hash();
        new ObjectStore(storage).store(hash,content.bytes());
        new SnapshotStore(storage).save(new Snapshot("active","NONE",1,"active",Map.of("file.txt",hash)));
        Files.writeString(storage.resolve("HEAD"),"active");
        new StagingArea(storage).stage(Path.of("file.txt"),hash);
    }

    @ParameterizedTest @ValueSource(strings={"staging","snapshots","objects","config","HEAD","staging/index","staging/commit.pending","mutation.lock","snapshots/active.snapshot"})
    void junctionSubstitutionsAreRejectedByCommandsAndCoreWithoutTouchingTarget(String relative) throws Exception {
        Path link=storage.resolve(relative), outside=Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("sentinel"),"never modify");
        Path original=link.resolveSibling(link.getFileName()+"-saved");
        if(Files.exists(link,LinkOption.NOFOLLOW_LINKS)) Files.move(link,original);
        junction(link,outside);
        try {
            var external=RepositoryPreflightTest.tree(outside);
            var before=RepositoryPreflightTest.tree(storage);
            for(String command:List.of("init","add","commit","status")) {
                var result=RepositoryPreflightTest.cli(root,command);
                assertEquals(1,result.code(),command+": "+result.output());
                assertTrue(result.output().toLowerCase(Locale.ROOT).matches("(?s).*(unsafe|reparse|junction|wrong type).*"),result.output());
                assertEquals(external,RepositoryPreflightTest.tree(outside));
                assertEquals(before,RepositoryPreflightTest.tree(storage));
            }
            assertThrows(Exception.class,()->new StagingArea(storage).stage(Path.of("other"),hash));
            assertThrows(Exception.class,()->new StagingArea(storage).clear());
            assertThrows(Exception.class,()->new StagingArea(storage).recover());
            assertThrows(Exception.class,()->new SnapshotStore(storage).save(new Snapshot("other","active",2,"other",Map.of())));
            assertThrows(Exception.class,()->new SnapshotStore(storage).load("active"));
            assertEquals(external,RepositoryPreflightTest.tree(outside));
            assertEquals(before,RepositoryPreflightTest.tree(storage));
        } finally { Files.delete(link); }
    }

    @ParameterizedTest @ValueSource(strings={"config","HEAD","staging/index","staging/commit.pending","mutation.lock","snapshots/active.snapshot"})
    void symbolicMetadataFilesAreRejectedWherePrivilegesPermit(String relative) throws Exception {
        Path target=Files.writeString(temp.resolve("outside.txt"),"preserve outside bytes");
        Path link=storage.resolve(relative);
        if(Files.exists(link,LinkOption.NOFOLLOW_LINKS)) Files.move(link,link.resolveSibling(link.getFileName()+"-saved"));
        try { Files.createSymbolicLink(link,target); }
        catch(IOException|UnsupportedOperationException failure){assumeTrue(false,"Symbolic link privilege unavailable: "+failure.getMessage());}
        try {
            var before=RepositoryPreflightTest.tree(storage);
            for(String command:List.of("init","add","commit","status")) {
                assertEquals(1,RepositoryPreflightTest.cli(root,command).code());
                assertEquals("preserve outside bytes",Files.readString(target));
                assertEquals(before,RepositoryPreflightTest.tree(storage));
            }
        } finally { Files.deleteIfExists(link); }
    }

    @Test void directStorageJunctionIsRejectedButProjectAncestorAliasIsSupported() throws Exception {
        Path alias=temp.resolve("project-alias");
        junction(alias,root);
        try {
            for(String command:List.of("init","status","add","commit")) {
                var result=RepositoryPreflightTest.cli(alias,command);
                assertEquals(0,result.code(),result.output());
            }
        } finally { Files.delete(alias); }
        Path metadataAlias=temp.resolve("metadata-alias");
        junction(metadataAlias,storage);
        try {
            var before=RepositoryPreflightTest.tree(storage);
            assertThrows(IOException.class,()->RepositoryMutationGuard.acquire(metadataAlias));
            assertThrows(IOException.class,()->RepositoryLayout.validate(metadataAlias));
            assertEquals(before,RepositoryPreflightTest.tree(storage));
        } finally { Files.delete(metadataAlias); }
    }

    @Test void guardedReadRechecksAParentSubstitutedAfterGuardAcquisition() throws Exception {
        Path outside=Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("index"),"must not read or clear");
        var before=RepositoryPreflightTest.tree(outside);
        Path staging=storage.resolve("staging"),saved=storage.resolve("staging-saved");
        try(var guard=RepositoryMutationGuard.acquire(storage)) {
            Files.move(staging,saved);
            junction(staging,outside);
            try {
                assertThrows(IOException.class,()->new StagingArea(storage).readAllLocked());
                assertThrows(IOException.class,()->new CommitRecovery(storage,new AtomicFilePublisher()).recover());
                assertEquals(before,RepositoryPreflightTest.tree(outside));
            } finally { Files.delete(staging); Files.move(saved,staging); }
        }
    }

    static void junction(Path link,Path target) throws Exception {
        Process p=new ProcessBuilder("cmd.exe","/d","/c","mklink /J \""+link+"\" \""+target+"\"").redirectErrorStream(true).start();
        try {
            assertTrue(p.waitFor(10,TimeUnit.SECONDS),"junction creation timed out");
            String output=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assumeTrue(p.exitValue()==0,"Junction unavailable: "+output);
        } finally {if(p.isAlive()) p.destroyForcibly();}
    }
}
