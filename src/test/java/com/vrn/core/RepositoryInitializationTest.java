package com.vrn.core;

import com.vrn.VrnApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryInitializationTest {

    @TempDir
    Path root;

    @Test
    void freshInitialization_createsAUsableVersionOneRepository() throws Exception {
        Run result = cli("init");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Initialized empty VRN repository."));
        Path storage = root.resolve(".vrn");
        RepositoryLayout.validate(storage);
        assertEquals("NONE", Files.readString(storage.resolve("HEAD")));
        assertEquals("version=1", Files.readString(storage.resolve("config")));
        assertEquals(".vrn/\n", Files.readString(root.resolve(".vrnignore")));
        assertFalse(Files.exists(storage.resolve("staging/index")));
        assertFalse(Files.exists(root.resolve(".vrnauto")));
        assertTrue(new StagingArea(storage).readAll().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "# User rules\r\n\r\n  *.log  \r\nbuild/\r\n",
            "# Unicode: नमस्ते\n\tcache/ \n*.tmp"})
    void preexistingIgnore_isPreservedByteForByte(String content) throws Exception {
        byte[] before = content.getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve(".vrnignore"), before);

        Run result = cli("init");

        assertEquals(0, result.exitCode(), result.output());
        assertArrayEquals(before, Files.readAllBytes(root.resolve(".vrnignore")));
        RepositoryLayout.validate(root.resolve(".vrn"));
    }

    @Test
    void ignoreDirectory_isRejectedBeforeCreatingStorage() throws Exception {
        Files.createDirectory(root.resolve(".vrnignore"));
        Files.writeString(root.resolve(".vrnignore/user.txt"), "preserve me");
        Map<String, String> before = tree();

        Run result = cli("init");

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains(".vrnignore is not a regular file"));
        assertEquals(before, tree());
        assertFalse(Files.exists(root.resolve(".vrn")));
    }

    @Test
    void repeatedInitialization_preservesHistoryStagingObjectsAndAllConfiguration() throws Exception {
        assertTrue(new RepositoryInitializer().initialize(root));
        Path storage = root.resolve(".vrn");
        byte[] original = "committed project bytes".getBytes(StandardCharsets.UTF_8);
        Path source = Files.write(root.resolve("ordinary.txt"), original);
        String hash = new FileHasher().hash(source);
        new ObjectStore(storage).store(hash, original);
        new StagingArea(storage).stage(Path.of("ordinary.txt"), hash);
        String active = new CommitService(storage).commit("existing history").snapshotId();
        new StagingArea(storage).stage(Path.of("next.txt"), hash);
        Files.writeString(storage.resolve("config"), "# local settings\r\nversion = 1\r\nowner=Oggy\r\n");
        Files.writeString(root.resolve(".vrnignore"), "# keep exactly\r\n*.log\r\n");
        Files.createDirectory(root.resolve(".vrnauto"));
        Files.writeString(root.resolve(".vrnauto/HEAD"), "independent state");
        Files.writeString(storage.resolve("user-note"), "unrecognized files are retained");
        Map<String, String> before = tree();

        assertFalse(new RepositoryInitializer().initialize(root));
        Run result = cli("init");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("already initialized and valid"));
        assertEquals(before, tree());
        assertEquals(active, Files.readString(storage.resolve("HEAD")));
        assertEquals(Map.of("ordinary.txt", hash), new SnapshotStore(storage).load(active).files());
        assertEquals(Map.of("next.txt", hash), new StagingArea(storage).readAll());
        assertArrayEquals(original, Files.readAllBytes(storage.resolve("objects").resolve(hash)));
    }

    @Test
    void existingValidStorageWithMissingIgnore_isNotSilentlyRepaired() throws Exception {
        new RepositoryInitializer().initialize(root);
        Files.delete(root.resolve(".vrnignore"));
        Map<String, String> before = tree();

        Run result = cli("init");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(before, tree());
        RepositoryLayout.validate(root.resolve(".vrn"));
        assertEquals(1, cli("add", ".").exitCode());
        assertEquals(1, cli("status").exitCode());
        assertFalse(Files.exists(root.resolve(".vrnignore")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "empty", "partial"})
    void existingInvalidStorage_isRejectedWithoutChangingItsContents(String state) throws Exception {
        Path storage = root.resolve(".vrn");
        if (state.equals("file")) {
            Files.writeString(storage, "ordinary user file");
        } else {
            Files.createDirectory(storage);
            if (state.equals("partial")) {
                Files.createDirectory(storage.resolve("objects"));
                Files.writeString(storage.resolve("objects/valuable"), "preserve partial contents");
                Files.writeString(storage.resolve("HEAD"), "NONE");
            }
        }
        Files.writeString(root.resolve(".vrnignore"), "# existing user rules\n");
        Map<String, String> before = tree();

        Run result = cli("init");

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("Invalid or incomplete VRN repository"));
        assertTrue(result.output().contains("Explicit recovery is required"));
        assertFalse(result.output().contains("Initialized empty"));
        assertFalse(result.output().contains("already initialized and valid"));
        assertEquals(before, tree());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "config"})
    void publicationFailure_leavesIncompleteStorageAndPreservesUserConfiguration(String failedFile) throws Exception {
        byte[] ignore = "# original\r\n*.log\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve(".vrnignore"), ignore);
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                if (destination.getFileName().toString().equals(failedFile)) {
                    throw new IOException("injected " + failedFile + " publication failure");
                }
                super.move(temporary, destination);
            }
        };

        IOException error = assertThrows(IOException.class,
                () -> new RepositoryInitializer(publisher).initialize(root));

        assertTrue(error.getMessage().contains("injected"));
        assertArrayEquals(ignore, Files.readAllBytes(root.resolve(".vrnignore")));
        assertFalse(Files.exists(root.resolve(".vrn/config")));
        assertThrows(IOException.class, () -> RepositoryLayout.validate(root.resolve(".vrn")));
        assertNoTemporaries();
        Map<String, String> partial = tree();
        assertEquals(1, cli("init").exitCode());
        assertEquals(partial, tree());
    }

    @Test
    void interruptionDuringTemporaryWrite_doesNotPublishACompletedRepository() throws Exception {
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void write(Path temporary, byte[] content) throws IOException {
                Files.writeString(temporary, "partial");
                throw new SimulatedInterruption();
            }
        };

        assertThrows(SimulatedInterruption.class, () -> new RepositoryInitializer(publisher).initialize(root));

        assertFalse(Files.exists(root.resolve(".vrn/HEAD")));
        assertFalse(Files.exists(root.resolve(".vrn/config")));
        assertFalse(Files.exists(root.resolve(".vrnignore")));
        assertThrows(IOException.class, () -> RepositoryLayout.validate(root.resolve(".vrn")));
        assertNoTemporaries();
    }

    @Test
    void ignoreCreatedAfterPreflight_isPreservedByExclusiveCreation() throws Exception {
        byte[] userRules = "# created concurrently\r\n*.bak\r\n".getBytes(StandardCharsets.UTF_8);
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                super.move(temporary, destination);
                if (destination.getFileName().toString().equals("HEAD")) {
                    Files.write(root.resolve(".vrnignore"), userRules);
                }
            }
        };

        assertTrue(new RepositoryInitializer(publisher).initialize(root));

        assertArrayEquals(userRules, Files.readAllBytes(root.resolve(".vrnignore")));
        RepositoryLayout.validate(root.resolve(".vrn"));
    }

    @Test
    void ignoreCreationFailure_afterHeadPublication_cannotMarkInitializationComplete() throws Exception {
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                super.move(temporary, destination);
                if (destination.getFileName().toString().equals("HEAD")) {
                    Files.createDirectory(root.resolve(".vrnignore"));
                    Files.writeString(root.resolve(".vrnignore/user-note"), "preserve this");
                }
            }
        };

        assertThrows(IOException.class, () -> new RepositoryInitializer(publisher).initialize(root));

        assertEquals("NONE", Files.readString(root.resolve(".vrn/HEAD")));
        assertEquals("preserve this", Files.readString(root.resolve(".vrnignore/user-note")));
        assertFalse(Files.exists(root.resolve(".vrn/config")));
        assertThrows(IOException.class, () -> RepositoryLayout.validate(root.resolve(".vrn")));
    }

    @Test
    void completionConfigIsPublishedOnlyAfterAllOtherInitializationWork() throws Exception {
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                assertFalse(Files.exists(root.resolve(".vrn/config")));
                if (destination.getFileName().toString().equals("config")) {
                    assertEquals("NONE", Files.readString(root.resolve(".vrn/HEAD")));
                    assertTrue(Files.isDirectory(root.resolve(".vrn/objects")));
                    assertTrue(Files.isDirectory(root.resolve(".vrn/snapshots")));
                    assertTrue(Files.isDirectory(root.resolve(".vrn/staging")));
                    assertEquals(".vrn/\n", Files.readString(root.resolve(".vrnignore")));
                    throw new AtomicMoveNotSupportedException(temporary.toString(), destination.toString(), "injected");
                }
                super.move(temporary, destination);
            }
        };

        assertThrows(AtomicMoveNotSupportedException.class,
                () -> new RepositoryInitializer(publisher).initialize(root));
        assertFalse(Files.exists(root.resolve(".vrn/config")));
        assertThrows(IOException.class, () -> RepositoryLayout.validate(root.resolve(".vrn")));
        assertNoTemporaries();
    }

    @Test
    void errorAfterFinalRename_leavesCompleteStorageThatCanBeValidatedOnRetry() throws Exception {
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                super.move(temporary, destination);
                if (destination.getFileName().toString().equals("config")) {
                    throw new IOException("injected error after completion rename took effect");
                }
            }
        };

        assertThrows(IOException.class, () -> new RepositoryInitializer(publisher).initialize(root));

        RepositoryLayout.validate(root.resolve(".vrn"));
        Map<String, String> before = tree();
        assertFalse(new RepositoryInitializer().initialize(root));
        assertEquals(0, cli("init").exitCode());
        assertEquals(before, tree());
    }

    @Test
    void preservedUserIgnoreStillAllowsMandatoryProtectionOfBothStorageRoots() throws Exception {
        Files.writeString(root.resolve(".vrnignore"), "# user rules only\n*.log\n");
        Files.createDirectories(root.resolve(".vrnauto/objects"));
        Files.writeString(root.resolve(".vrnauto/objects/private"), "independent automatic bytes");
        Files.writeString(root.resolve("ordinary.txt"), "ordinary bytes");
        new RepositoryInitializer().initialize(root);
        Set<String> expected = Set.of(".vrnignore", "ordinary.txt");
        assertEquals(expected, new FileScanner().scan(root, Path.of(".")).stream()
                .map(file -> file.path().toString()).collect(Collectors.toSet()));
        assertEquals(expected, Set.copyOf(WorkingTreeStatus.compute(root).untracked));
        assertEquals(0, cli("add", ".").exitCode());
        assertEquals(expected, new StagingArea(root.resolve(".vrn")).readAll().keySet());
        assertEquals("independent automatic bytes", Files.readString(root.resolve(".vrnauto/objects/private")));
    }

    private Map<String, String> tree() throws IOException {
        Map<String, String> result = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                String key = root.relativize(path).toString();
                result.put(key, Files.isDirectory(path) ? "directory"
                        : Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    private void assertNoTemporaries() throws IOException {
        try (var paths = Files.walk(root)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private Run cli(String... args) throws Exception {
        String classpath = location(VrnApplication.class) + File.pathSeparator + location(CommandLine.class);
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        command.addAll(Arrays.asList("-cp", classpath, VrnApplication.class.getName()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI did not complete");
            return new Run(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private record Run(int exitCode, String output) {
    }

    private static final class SimulatedInterruption extends Error {
    }
}
