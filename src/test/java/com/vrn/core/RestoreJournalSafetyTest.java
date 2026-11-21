package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RestoreJournalSafetyTest {
    @TempDir Path root;
    RestoreExecutionTest f;
    Path storage;
    @BeforeEach void initialize() throws Exception { f=new RestoreExecutionTest(); f.root=root; f.initialize(); f.replacement(); storage=f.storage; }
    static final class Crash extends Error {}
    void interrupt() throws Exception {
        var ops=new RestoreFileOps(root) { @Override protected void checkpoint(Event e,String d) { if(e==Event.BEFORE_STEP) throw new Crash(); } };
        assertThrows(Crash.class,()->new RestoreService(root,ops).restore("old"));
    }
    void invalid(String text) throws Exception {
        int checksum=text.lastIndexOf("checksum\t"); String body=text.substring(0,checksum);
        Files.writeString(storage.resolve("restore.pending"),body+"checksum\t"+RestoreJournal.digest(body.getBytes(StandardCharsets.UTF_8))+"\n");
        var before=RepositoryPreflightTest.tree(root); assertThrows(Exception.class,()->new RestoreService(root).recover()); assertEquals(before,RepositoryPreflightTest.tree(root));
    }

    @ParameterizedTest @ValueSource(strings={"header","phase","cursor","flag","duplicate","base64","traversal","internal","catalog-id","target-hash","root"})
    void corruptOrUnsafeJournalFieldsNeverDriveRecovery(String fault) throws Exception {
        interrupt(); String text=Files.readString(storage.resolve("restore.pending"));
        String alpha=Base64.getEncoder().encodeToString("Alpha".getBytes(StandardCharsets.UTF_8));
        String changed=switch(fault) {
            case "header" -> text.replace("VRN-RESTORE\t1","VRN-RESTORE\t99");
            case "phase" -> text.replace("phase\tAPPLYING","phase\tUNKNOWN");
            case "cursor" -> text.replace("cursor\t0","cursor\t999");
            case "flag" -> text.replace("in-flight\ttrue","in-flight\tmaybe");
            case "duplicate" -> text.replace("cursor\t0","cursor\t0\ncursor\t0");
            case "base64" -> text.replace("original\t0\t"+alpha,"original\t0\t%%%");
            case "traversal" -> text.replace("original\t0\t"+alpha,"original\t0\t"+Base64.getEncoder().encodeToString("../outside".getBytes(StandardCharsets.UTF_8)));
            case "internal" -> text.replace("original\t0\t"+alpha,"original\t0\t"+Base64.getEncoder().encodeToString(".vrn/HEAD".getBytes(StandardCharsets.UTF_8)));
            case "catalog-id" -> text.replace("original\t0\t","original\t9\t");
            case "target-hash" -> {
                var journal=RestoreJournal.decode(new MetadataPaths(storage).readLines("restore.pending")); String hash=journal.targets().getFirst().hash();
                yield text.replace("target-file\t0\t"+alpha+"\t"+hash,"target-file\t0\t"+alpha+"\t"+"0".repeat(64));
            }
            default -> text.replace("root\t"+Base64.getEncoder().encodeToString(root.toRealPath().toString().getBytes(StandardCharsets.UTF_8)),"root\t"+Base64.getEncoder().encodeToString(root.resolveSibling("other").toString().getBytes(StandardCharsets.UTF_8)));
        };
        assertNotEquals(text,changed,fault); invalid(changed);
    }

    @ParameterizedTest @ValueSource(strings={"backup","work-area","journal"})
    void recoveryJunctionsNeverReachOutsideData(String target) throws Exception {
        interrupt(); Path outside=Files.createDirectory(root.resolve("outside")); Files.writeString(outside.resolve("sentinel"),"preserve");
        Path link=target.equals("backup")?storage.resolve("restore-work/backup/0"):target.equals("journal")?storage.resolve("restore.pending"):storage.resolve("restore-work");
        Path saved=root.resolve("saved-"+target);
        assertTrue(link.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())); assertTrue(saved.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()));
        Files.move(link,saved); WindowsMetadataBoundaryTest.junction(link,outside);
        try {
            var before=RepositoryPreflightTest.tree(root); assertThrows(Exception.class,()->new RestoreService(root).recover());
            assertEquals(before,RepositoryPreflightTest.tree(root)); assertEquals("preserve",Files.readString(outside.resolve("sentinel")));
        } finally { Files.delete(link); Files.move(saved,link); }
    }

    @Test void uncommittedStagingIsRetainedExactlyAcrossRestoreAndRecovery() throws Exception {
        f.write("unrelated","selection"); new AddService(root).add(Path.of("unrelated")); byte[] index=Files.readAllBytes(storage.resolve("staging/index"));
        interrupt(); new RestoreService(root).recover(); assertArrayEquals(index,Files.readAllBytes(storage.resolve("staging/index")));
        new RestoreService(root).restore("old"); assertArrayEquals(index,Files.readAllBytes(storage.resolve("staging/index")));
    }

    @Test void symbolicWorkingFileIsRefusedWherePrivilegesPermit() throws Exception {
        Path original=root.resolve("Alpha"); Files.delete(original); Path outside=f.write("outside-file","preserve");
        try { Files.createSymbolicLink(original,outside); }
        catch(Exception unsupported) { org.junit.jupiter.api.Assumptions.assumeTrue(false,"Symbolic link privilege unavailable: "+unsupported); }
        try { assertThrows(Exception.class,()->new RestoreService(root).restore("old")); assertEquals("preserve",Files.readString(outside)); }
        finally { Files.deleteIfExists(original); }
    }

    @Test void symbolicRecoveryCopyIsRefusedWherePrivilegesPermit() throws Exception {
        interrupt(); Path backup=storage.resolve("restore-work/backup/0"); Files.delete(backup); Path outside=f.write("outside-file","preserve");
        try { Files.createSymbolicLink(backup,outside); }
        catch(Exception unsupported) { org.junit.jupiter.api.Assumptions.assumeTrue(false,"Symbolic link privilege unavailable: "+unsupported); }
        try { assertThrows(Exception.class,()->new RestoreService(root).recover()); assertEquals("preserve",Files.readString(outside)); }
        finally { Files.deleteIfExists(backup); }
    }
}
