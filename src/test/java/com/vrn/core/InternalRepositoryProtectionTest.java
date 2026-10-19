package com.vrn.core;

import com.vrn.VrnApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class InternalRepositoryProtectionTest {

    @TempDir
    Path root;
    private Path manual;
    private Path automatic;

    @BeforeEach
    void initialize() throws Exception {
        manual = root.resolve(".vrn");
        automatic = root.resolve(".vrnauto");
        write(".vrn/HEAD", "NONE");
        write(".vrn/config", "version=1");
        write(".vrn/objects/private", "manual object");
        write(".vrn/snapshots/private", "manual snapshot");
        Files.createDirectories(manual.resolve("staging"));
        write(".vrnauto/HEAD", "independent automatic pointer");
        write(".vrnauto/objects/private", "automatic object");
        write(".vrnauto/snapshots/private", "automatic snapshot");
        write(".vrnignore", "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "*.log\n"})
    void scannerExcludesBothInternalTrees_withoutReservedIgnoreRules(String rules) throws Exception {
        write(".vrnignore", rules);
        write("ordinary.txt", "project content");

        assertEquals(Set.of(".vrnignore", "ordinary.txt"), scanned(Path.of(".")));
        assertEquals(List.of(".vrnignore", "ordinary.txt"), WorkingTreeStatus.compute(root).untracked);
    }

    @Test
    void nestedNamesAndSimilarPrefixes_remainOrdinaryProjectContent() throws Exception {
        write(".vrnignore", ".vrn/\n.vrnauto/\n");
        write("src/.vrn/example.txt", "nested manual name");
        write("src/.vrnauto/example.txt", "nested automatic name");
        write(".vrn-backup/file.txt", "backup");
        write(".vrnauto.txt", "similar name");
        Set<String> expected = Set.of(".vrnignore", "src/.vrn/example.txt",
                "src/.vrnauto/example.txt", ".vrn-backup/file.txt", ".vrnauto.txt");

        assertEquals(expected, scanned(Path.of(".")));
        assertEquals(Set.of("src/.vrn/example.txt"), scanned(Path.of("src/.vrn")));
        assertEquals(Set.of("src/.vrnauto/example.txt"), scanned(Path.of("src/.vrnauto")));
        assertEquals(expected, Set.copyOf(WorkingTreeStatus.compute(root).untracked));

        Run added = cli("add", ".");
        assertEquals(0, added.exitCode(), added.output());
        assertEquals(expected, new StagingArea(manual).readAll().keySet());
    }

    @ParameterizedTest
    @ValueSource(strings = {".vrn", ".vrnauto"})
    void traversalCallbackPrunesReservedRoot_beforeVisitingItsChildren(String name) throws Exception {
        ProjectFileWalker walker = new ProjectFileWalker(root, new VrnIgnore(root.resolve(".vrnignore")));
        Path internal = root.resolve(name);
        Path nested = Files.createDirectories(root.resolve("src").resolve(name));

        assertEquals(FileVisitResult.SKIP_SUBTREE,
                walker.preVisitDirectory(internal, Files.readAttributes(internal, BasicFileAttributes.class)));
        assertEquals(FileVisitResult.CONTINUE,
                walker.preVisitDirectory(root, Files.readAttributes(root, BasicFileAttributes.class)));
        assertEquals(FileVisitResult.CONTINUE,
                walker.preVisitDirectory(nested, Files.readAttributes(nested, BasicFileAttributes.class)));
    }

    @ParameterizedTest
    @CsvSource({
            ".vrn, false", ".vrn/HEAD, false", ".vrnauto, false", ".vrnauto/snapshots, false",
            ".vrn, true", ".vrn/HEAD, true", ".vrnauto, true", ".vrnauto/snapshots, true"
    })
    void explicitCliTargetsAreRejected_withoutChangingStagingOrObjects(String target, boolean missingIgnore)
            throws Exception {
        new StagingArea(manual).stage(Path.of("existing.txt"), "existing-hash");
        byte[] before = Files.readAllBytes(manual.resolve("staging/index"));
        if (missingIgnore) {
            Files.delete(root.resolve(".vrnignore"));
        }

        Run result = cli("add", target);

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("Cannot add reserved internal repository path"), result.output());
        assertFalse(result.output().contains("Files staged successfully"));
        assertArrayEquals(before, Files.readAllBytes(manual.resolve("staging/index")));
        assertEquals(Map.of("existing.txt", "existing-hash"), new StagingArea(manual).readAll());
        assertEquals(Set.of("private"), objectNames(manual));
        assertEquals(Set.of("private"), objectNames(automatic));
    }

    @Test
    void normalizedAbsoluteAndPlatformPaths_cannotBypassProtection() throws Exception {
        List<Path> targets = List.of(root.resolve(".vrn/HEAD"), root.resolve(".vrnauto/objects"),
                Path.of("src", "..", ".vrn", "HEAD"), Path.of(".", ".vrnauto", "snapshots"),
                Path.of(".vrn", "absent-child"));
        for (Path target : targets) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new FileScanner().scan(root.resolve("src/.."), target));
            assertTrue(error.getMessage().contains("reserved internal repository path"), error.getMessage());
        }
    }

    @Test
    void reservedPathCase_followsPlatformPathIdentity() throws Exception {
        Path upperCase = root.resolve(".VRN");
        if (upperCase.equals(manual)) {
            assertThrows(IllegalArgumentException.class, () -> new FileScanner().scan(root, upperCase));
        } else {
            write(".VRN/ordinary.txt", "distinct name on a case-sensitive platform");
            assertEquals(Set.of(".VRN/ordinary.txt"), scanned(upperCase));
        }
    }

    @Test
    void actualAddCommitAndStatus_excludeInternalChangesAndKeepStorageIndependent() throws Exception {
        write("ordinary.txt", "project bytes");
        byte[] autoHead = Files.readAllBytes(automatic.resolve("HEAD"));

        Run add = cli("add", ".");
        assertEquals(0, add.exitCode(), add.output());
        Map<String, String> entries = new StagingArea(manual).readAll();
        assertEquals(Set.of(".vrnignore", "ordinary.txt"), entries.keySet());
        assertEquals(new FileHasher().hash(root.resolve("ordinary.txt")), entries.get("ordinary.txt"));
        assertArrayEquals(Files.readAllBytes(root.resolve("ordinary.txt")),
                Files.readAllBytes(manual.resolve("objects").resolve(entries.get("ordinary.txt"))));
        assertEquals(Set.of("private", entries.get(".vrnignore"), entries.get("ordinary.txt")), objectNames(manual));
        assertEquals(Set.of("private"), objectNames(automatic));
        assertArrayEquals(autoHead, Files.readAllBytes(automatic.resolve("HEAD")));

        Run commit = cli("commit", "-m", "ordinary files only");
        assertEquals(0, commit.exitCode(), commit.output());
        String manualHead = Files.readString(manual.resolve("HEAD"));
        assertEquals(entries, new SnapshotStore(manual).load(manualHead).files());

        write(".vrn/config", "changed bookkeeping");
        write(".vrn/objects/new-internal-object", "new manual object");
        write(".vrnauto/HEAD", "changed automatic pointer");
        write(".vrnauto/snapshots/new", "new automatic metadata");
        assertTrue(WorkingTreeStatus.compute(root).isClean());
        Run status = cli("status");
        assertEquals(0, status.exitCode(), status.output());
        assertEquals("Nothing to commit, working directory clean.", status.output().strip());
        assertEquals(manualHead, Files.readString(manual.resolve("HEAD")));
    }

    @Test
    void missingIgnoreFailsClosed_forWholeProjectAddAndStatus() throws Exception {
        write("ordinary.txt", "must not be staged by a failed scan");
        Files.delete(root.resolve(".vrnignore"));

        assertThrows(NoSuchFileException.class, () -> new FileScanner().scan(root, Path.of(".")));
        assertThrows(NoSuchFileException.class, () -> WorkingTreeStatus.compute(root));
        Run add = cli("add", ".");
        assertEquals(1, add.exitCode(), add.output());
        assertTrue(add.output().contains("Failed to add files:"));
        assertTrue(add.output().contains(".vrnignore"));
        Run status = cli("status");
        assertEquals(1, status.exitCode(), status.output());
        assertTrue(status.output().contains("Failed to get status:"));
        assertFalse(status.output().contains("working directory clean"));
        assertTrue(new StagingArea(manual).readAll().isEmpty());
        assertFalse(Files.exists(manual.resolve("staging/index")));
        assertEquals(Set.of("private"), objectNames(manual));
        assertEquals(Set.of("private"), objectNames(automatic));
    }

    @Test
    void ordinaryIgnoreRulesStillApply_includingToNestedReservedNames() throws Exception {
        write(".vrnignore", "*.log\nsrc/.vrn/\n");
        write("debug.log", "ignored");
        write("src/.vrn/example.txt", "ignored nested path");
        write("src/.vrnauto/example.txt", "visible nested path");
        write("ordinary.txt", "visible ordinary file");
        Set<String> expected = Set.of(".vrnignore", "src/.vrnauto/example.txt", "ordinary.txt");

        assertEquals(expected, scanned(Path.of(".")));
        assertEquals(expected, Set.copyOf(WorkingTreeStatus.compute(root).untracked));
    }

    @Test
    void targetsOutsideRootOrMissing_stillFail() {
        assertThrows(IllegalArgumentException.class, () -> new FileScanner().scan(root, Path.of("..")));
        assertThrows(IllegalArgumentException.class, () -> new FileScanner().scan(root, Path.of("missing.txt")));
    }

    private Set<String> scanned(Path target) throws Exception {
        return new FileScanner().scan(root, target).stream()
                .map(file -> file.path().toString().replace('\\', '/')).collect(Collectors.toSet());
    }

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Set<String> objectNames(Path storage) throws Exception {
        try (var paths = Files.list(storage.resolve("objects"))) {
            return paths.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
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
}
