package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Windows integration, separate from host-independent manifest semantics. */
@EnabledOnOs(OS.WINDOWS)
class WindowsFilesystemCompatibilityTest {

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {" leading.txt", "del-\u007f.txt", "c1-\u0085.txt", "CON\u2000.txt",
            "\u00a0name.txt", "name.txt\u3000", "資料/😀=é.txt", "#notes.txt", "config=production.txt",
            "[]{}+!@;$&()%,.txt", " .vrn/safe.txt"})
    void realNamesAreReadStagedAndCommittedWithoutChangingTheirSpelling(String name) throws Exception {
        new RepositoryInitializer().initialize(root);
        Path source = root.resolve(name);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "actual Windows contents", StandardOpenOption.CREATE_NEW);

        List<ScannedFile> scanned = new FileScanner().scan(root, Path.of(name));

        assertEquals(1, scanned.size());
        ScannedFile file = scanned.getFirst();
        assertEquals(name, file.path().toString().replace('\\', '/'));
        assertArrayEquals(Files.readAllBytes(source), file.bytes());
        Path storage = root.resolve(".vrn");
        new ObjectStore(storage).store(file.hash(), file.bytes());
        new StagingArea(storage).stage(file.path(), file.hash());
        String id = new CommitService(storage).commit("Windows filename").snapshotId();
        assertEquals(Set.of(name), new SnapshotStore(storage).load(id).files().keySet());
    }

    @Test
    void scannerUsesActualDirectorySpellingForCaseInsensitiveLookups() throws Exception {
        new RepositoryInitializer().initialize(root);
        Files.createDirectory(root.resolve("Src"));
        Files.writeString(root.resolve("Src/A.java"), "A");
        assumeTrue(Files.exists(root.resolve("src")), "This test requires a case-insensitive Src lookup");
        Files.writeString(root.resolve("src/B.java"), "B");

        List<ScannedFile> files = new FileScanner().scan(root, Path.of("src"));

        assertEquals(Set.of("Src/A.java", "Src/B.java"), names(files));
        assertEquals("Src/A.java", new FileScanner().scan(root, Path.of("sRC/a.JAVA"))
                .getFirst().path().toString().replace('\\', '/'));
    }

    @Test
    void explicitCaseAliasDoesNotBypassAnIgnoreRuleForTheActualName() throws Exception {
        new RepositoryInitializer().initialize(root);
        Files.writeString(root.resolve("debug.log"), "ignored contents");
        assumeTrue(Files.exists(root.resolve("DEBUG.LOG")), "Case-insensitive lookup required");
        Files.writeString(root.resolve(".vrnignore"), "*.log\n");

        assertTrue(new FileScanner().scan(root, Path.of("DEBUG.LOG")).isEmpty());
    }

    @Test
    void resolvedContainmentAcceptsTheSameRootWithDifferentDriveLetterCase() throws Exception {
        new RepositoryInitializer().initialize(root);
        Files.writeString(root.resolve("actual.txt"), "inside the chosen root");
        String spelling = root.toString();
        Path alternateRoot = Path.of(Character.toLowerCase(spelling.charAt(0)) + spelling.substring(1));

        List<ScannedFile> files = new FileScanner().scan(alternateRoot, root.resolve("actual.txt"));

        assertEquals(Set.of("actual.txt"), names(files));
        assertEquals("inside the chosen root", new String(files.getFirst().bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void directoryEntryCachesLastOnlyForOneScan() throws Exception {
        new RepositoryInitializer().initialize(root);
        Files.writeString(root.resolve("first.txt"), "first");
        FileScanner scanner = new FileScanner();
        assertTrue(names(scanner.scan(root, Path.of("."))).contains("first.txt"));
        Files.writeString(root.resolve("second.txt"), "second");

        assertTrue(names(scanner.scan(root, Path.of("."))).contains("second.txt"));
    }

    @Test
    void twoHardLinkNamesRemainIndependentManifestDestinations() throws Exception {
        new RepositoryInitializer().initialize(root);
        Path first = Files.writeString(root.resolve("first.txt"), "same physical file");
        Path second = root.resolve("second.txt");
        try {
            Files.createLink(second, first);
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Hard links unavailable: " + unavailable.getMessage());
        }
        assertTrue(Files.isSameFile(first, second));
        List<ScannedFile> files = new FileScanner().scan(root, Path.of(".")).stream()
                .filter(file -> file.path().toString().endsWith(".txt")).toList();
        assertEquals(Set.of("first.txt", "second.txt"), names(files));
        Path storage = root.resolve(".vrn");
        for (ScannedFile file : files) {
            new ObjectStore(storage).store(file.hash(), file.bytes());
            new StagingArea(storage).stage(file.path(), file.hash());
        }
        String id = new CommitService(storage).commit("two hard links").snapshotId();
        assertEquals(Set.of("first.txt", "second.txt"), new SnapshotStore(storage).load(id).files().keySet());
    }

    @Test
    void genuineCaseSensitiveFilesAreNotMisreportedAsAnOsFilenameRestriction() throws Exception {
        Path project = Files.createDirectory(root.resolve("case-sensitive"));
        enableCaseSensitivity(project);
        new RepositoryInitializer().initialize(project);
        Path lower = Files.writeString(project.resolve("a.txt"), "lower", StandardOpenOption.CREATE_NEW);
        Path upper = Files.writeString(project.resolve("A.txt"), "upper", StandardOpenOption.CREATE_NEW);
        assertNotEquals(Files.readString(lower), Files.readString(upper));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new FileScanner().scan(project, Path.of(".")));

        assertTrue(error.getMessage().contains("VRN support limitation"), error.getMessage());
        assertTrue(error.getMessage().contains("case-equivalent destination collision"));
        assertFalse(Files.exists(project.resolve(".vrn/staging/index")));
        assertEquals("NONE", Files.readString(project.resolve(".vrn/HEAD")));
    }

    @Test
    void caseInsensitivePathPrefixCannotEscapeIntoACaseSensitiveSibling() throws Exception {
        Path parent = Files.createDirectory(root.resolve("case-parent"));
        enableCaseSensitivity(parent);
        Path project = Files.createDirectory(parent.resolve("repo"));
        Path outside = Files.createDirectory(parent.resolve("REPO"));
        new RepositoryInitializer().initialize(project);
        Files.writeString(outside.resolve("outside.txt"), "must not be scanned");
        Files.writeString(project.resolve(".vrnignore"), "*.txt\n");

        IOException error = assertThrows(IOException.class,
                () -> new FileScanner().scan(project, outside.resolve("outside.txt")));

        assertTrue(error.getMessage().contains("Unsafe repository boundary"), error.getMessage());
        assertFalse(Files.exists(project.resolve(".vrn/staging/index")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonignoredSymbolicLinksFailClearly_insteadOfBeingFollowedOrSkipped(boolean directory) throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        new RepositoryInitializer().initialize(project);
        Path target = directory ? Files.createDirectory(root.resolve("outside"))
                : Files.writeString(root.resolve("outside.txt"), "outside contents");
        Path link = project.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symbolic links unavailable: " + unavailable.getMessage());
        }
        try {
            IOException error = assertThrows(IOException.class, () -> new FileScanner().scan(project, Path.of(".")));
            assertTrue(error.getMessage().contains("symbolic links and reparse entries are not supported"));
            Files.writeString(project.resolve(".vrnignore"), "link/\n");
            assertTrue(names(new FileScanner().scan(project, Path.of("."))).stream().noneMatch(name -> name.startsWith("link")));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void junctionsAreRejectedBeforeTraversingTheirTarget() throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        new RepositoryInitializer().initialize(project);
        Path target = Files.createDirectory(root.resolve("outside"));
        Files.writeString(target.resolve("private.txt"), "outside data");
        Path junction = project.resolve("junction");
        Run result = run("cmd.exe", "/d", "/c", "mklink /J \"" + junction + "\" \"" + target + "\"");
        assumeTrue(result.code() == 0, "Junction creation unavailable: " + result.output());
        try {
            IOException error = assertThrows(IOException.class, () -> new FileScanner().scan(project, Path.of(".")));
            assertTrue(error.getMessage().contains("VRN support limitation"), error.getMessage());
        } finally {
            Files.deleteIfExists(junction); // nonrecursive unlink; never delete the target tree
        }
    }

    private void enableCaseSensitivity(Path disposable) throws Exception {
        assertTrue(disposable.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()));
        Run result = run("fsutil.exe", "file", "setCaseSensitiveInfo", disposable.toString(), "enable");
        assumeTrue(result.code() == 0, "Disposable case-sensitive directory unavailable: " + result.output());
    }

    private Set<String> names(List<ScannedFile> files) {
        return files.stream().map(file -> file.path().toString().replace('\\', '/')).collect(Collectors.toSet());
    }

    private Run run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Filesystem experiment timed out");
            return new Run(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private record Run(int code, String output) { }
}
