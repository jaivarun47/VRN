package com.vrn.core;

import com.vrn.VrnApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryPreflightTest {
    @TempDir Path root;
    Path storage;
    String hash;

    @BeforeEach void initialize() throws Exception {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        Files.writeString(root.resolve("file.txt"), "captured content");
        var content = new FileHasher().hashWithBytes(root.resolve("file.txt"));
        hash = content.hash();
        new ObjectStore(storage).store(hash, content.bytes());
        new StagingArea(storage).stage(Path.of("file.txt"), hash);
    }

    static Stream<Arguments> invalidCommands() {
        return Stream.of("missing-config", "future-config", "malformed-config", "missing-objects",
                "missing-staging", "missing-head", "directory-config", "file-snapshots")
                .flatMap(state -> Stream.of("init", "add", "commit", "status").map(command -> Arguments.of(state, command)));
    }

    @ParameterizedTest @MethodSource("invalidCommands")
    void everyCommandRejectsInvalidLayoutWithoutPublishingOrCleaning(String state, String command) throws Exception {
        switch (state) {
            case "missing-config" -> Files.delete(storage.resolve("config"));
            case "future-config" -> Files.writeString(storage.resolve("config"), "version=999");
            case "malformed-config" -> Files.writeString(storage.resolve("config"), "version=1\nversion=1");
            case "missing-objects" -> { Files.delete(storage.resolve("objects/" + hash)); Files.delete(storage.resolve("objects")); }
            case "missing-staging" -> { Files.delete(storage.resolve("staging/index")); Files.delete(storage.resolve("staging")); }
            case "missing-head" -> Files.delete(storage.resolve("HEAD"));
            case "directory-config" -> { Files.delete(storage.resolve("config")); Files.createDirectory(storage.resolve("config")); }
            case "file-snapshots" -> { Files.delete(storage.resolve("snapshots")); Files.writeString(storage.resolve("snapshots"), "preserve"); }
            default -> throw new AssertionError(state);
        }
        Files.writeString(root.resolve("new.txt"), "must not publish a new object");
        Map<String, String> before = tree(storage);
        Run result = cli(root, command);
        assertEquals(1, result.code(), result.output());
        assertFalse(result.output().contains("working directory clean"), result.output());
        assertFalse(result.output().contains("Committed snapshot"), result.output());
        assertEquals(before, tree(storage), command + " changed invalid storage");
    }

    @ParameterizedTest
    @ValueSource(strings={"object", "stage", "clear", "recover", "snapshot", "guard", "commit", "status", "index-read", "snapshot-read"})
    void publicCoreEntryPointsCannotBypassFormatChecks(String operation) throws Exception {
        new SnapshotStore(storage).save(new Snapshot("existing", "NONE", 1, "existing", Map.of("file.txt", hash)));
        Files.delete(storage.resolve("config"));
        var before = tree(storage);
        assertThrows(Exception.class, () -> {
            switch(operation) {
                case "object" -> new ObjectStore(storage).store(hash, "captured content".getBytes(StandardCharsets.UTF_8));
                case "stage" -> new StagingArea(storage).stage(Path.of("other.txt"), hash);
                case "clear" -> new StagingArea(storage).clear();
                case "recover" -> new StagingArea(storage).recover();
                case "snapshot" -> new SnapshotStore(storage).save(new Snapshot("new", "NONE", 1, "new", Map.of()));
                case "guard" -> { try(var guard=RepositoryMutationGuard.acquire(storage)) { fail("acquired invalid storage"); } }
                case "commit" -> new CommitService(storage).commit("invalid");
                case "status" -> WorkingTreeStatus.compute(root);
                case "index-read" -> new StagingArea(storage).readAll();
                case "snapshot-read" -> new SnapshotStore(storage).load("existing");
                default -> throw new AssertionError(operation);
            }
        });
        assertEquals(before, tree(storage));
    }

    @Test void invalidFreshStorageDoesNotCreateALockBeforeRejection() throws Exception {
        Files.delete(storage.resolve("mutation.lock"));
        Files.writeString(storage.resolve("config"), "version=2");
        var before=tree(storage);
        assertThrows(IOException.class, () -> RepositoryMutationGuard.acquire(storage));
        assertEquals(before, tree(storage));
        assertFalse(Files.exists(storage.resolve("mutation.lock")));
    }

    @Test void missingIgnoreIsOptionalAndIsNeverRecreatedByCommands() throws Exception {
        Files.delete(root.resolve(".vrnignore"));
        Files.createDirectories(root.resolve(".vrnauto/objects"));
        Files.writeString(root.resolve(".vrnauto/objects/private"), "private");
        assertEquals(0, cli(root, "init").code());
        assertEquals(0, cli(root, "status").code());
        assertEquals(0, cli(root, "add").code());
        assertEquals(0, cli(root, "commit").code());
        assertFalse(Files.exists(root.resolve(".vrnignore")));
        var snapshot=new SnapshotStore(storage).load(Files.readString(storage.resolve("HEAD")));
        assertEquals(Set.of("file.txt"), snapshot.files().keySet());
        assertEquals("private", Files.readString(root.resolve(".vrnauto/objects/private")));
    }

    @Test void existingUnreadableIgnoreTypeIsNotTreatedAsMissing() throws Exception {
        Files.delete(root.resolve(".vrnignore"));
        Files.createDirectory(root.resolve(".vrnignore"));
        assertThrows(IOException.class, () -> new VrnIgnore(root.resolve(".vrnignore")));
        var before=tree(storage);
        assertEquals(1, cli(root, "add").code());
        assertEquals(1, cli(root, "status").code());
        assertEquals(before, tree(storage));
    }

    @Test void failedPreflightPreservesPendingRecoveryUntilValidRetry() throws Exception {
        AtomicFilePublisher failHead=new AtomicFilePublisher() {
            @Override protected void move(Path temporary, Path destination) throws IOException {
                if(destination.equals(storage.resolve("HEAD"))) throw new IOException("injected HEAD failure");
                super.move(temporary,destination);
            }
        };
        assertThrows(IOException.class, () -> new CommitService(storage,failHead).commit("pending"));
        assertTrue(Files.exists(storage.resolve("staging/commit.pending")));
        Files.writeString(storage.resolve("config"), "version=2");
        var before=tree(storage);
        assertThrows(IOException.class, () -> new StagingArea(storage).recover());
        assertThrows(IOException.class, () -> new CommitService(storage).commit("blocked"));
        assertEquals(before, tree(storage));
        Files.writeString(storage.resolve("config"), "version=1");
        var result=new CommitService(storage).commit("retry");
        assertEquals(result.snapshotId(), Files.readString(storage.resolve("HEAD")));
        assertFalse(Files.exists(storage.resolve("staging/commit.pending")));
        assertTrue(new StagingArea(storage).readAll().isEmpty());
    }

    @Test void validPendingPublishedCleanupStillRecoversWithoutTreatingItAsFreshStaging() throws Exception {
        AtomicFilePublisher failCleanup=new AtomicFilePublisher() {
            @Override public void delete(Path path) throws IOException {
                if(path.equals(storage.resolve("staging/index"))) throw new IOException("injected cleanup failure");
                super.delete(path);
            }
        };
        var result=new CommitService(storage,failCleanup).commit("published");
        assertNotNull(result.warning());
        RepositoryLayout.validate(storage);
        assertTrue(new StagingArea(storage).readAll().isEmpty());
        new StagingArea(storage).recover();
        assertEquals(result.snapshotId(), Files.readString(storage.resolve("HEAD")));
        assertFalse(Files.exists(storage.resolve("staging/commit.pending")));
    }

    @Test void validRepeatedInitializationPreservesEverything() throws Exception {
        var before=tree(storage);
        assertEquals(0, cli(root,"init").code());
        assertEquals(before, tree(storage));
    }

    static Map<String,String> tree(Path root) throws Exception {
        Map<String,String> result=new TreeMap<>();
        try(var paths=Files.walk(root)) {
            for(Path path:paths.toList()) {
                var attributes=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                String value=attributes.isSymbolicLink() || attributes.isOther() ? "link/reparse"
                        : attributes.isDirectory() ? "directory" : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
                result.put(root.relativize(path).toString(),value);
            }
        }
        return result;
    }

    static Run cli(Path root,String command) throws Exception {
        String cp=Path.of(VrnApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                +java.io.File.pathSeparator+Path.of(CommandLine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var arguments=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",cp,VrnApplication.class.getName(),command));
        if(command.equals("add")) arguments.add(".");
        if(command.equals("commit")) arguments.addAll(List.of("-m","preflight test"));
        Process process=new ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"CLI timed out");
            return new Run(process.exitValue(),new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    record Run(int code,String output) {}
}
