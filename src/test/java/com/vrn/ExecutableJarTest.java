package com.vrn;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Run through Gradle test: the task builds the artifact and supplies its exact path. */
class ExecutableJarTest {
    @TempDir Path temporary;

    @BeforeAll static void reportRuntime() {
        System.out.println("Packaged CLI test runtime: " + Runtime.version()
                + "; java.home=" + System.getProperty("java.home"));
    }

    @Test void artifactAndTestBytecodeTarget21_andRuntimeDependencyIsBundled() throws Exception {
        assertEquals(21, Runtime.version().feature(), "Run Gradle test with the configured Java 21 toolchain");
        int applicationClasses = 0;
        try (var jar = new JarFile(artifact().toFile())) {
            assertEquals("com.vrn.VrnApplication", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(jar.getJarEntry("picocli/CommandLine.class"), "The executable needs its runtime dependency");
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                // Java 21 ignores future versioned entries. Its effective MR classes are
                // additionally exercised by the actual Java 21 launch below.
                if (!name.endsWith(".class") || name.startsWith("META-INF/versions/")) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    int major = majorVersion(input);
                    if (name.startsWith("com/vrn/")) {
                        assertEquals(65, major, name + " must target Java 21");
                        applicationClasses++;
                    } else {
                        assertTrue(major <= 65, name + " requires a runtime newer than Java 21");
                    }
                }
            }
        }
        assertTrue(applicationClasses > 0);
        try (var input = ExecutableJarTest.class.getResourceAsStream("ExecutableJarTest.class")) {
            assertNotNull(input);
            assertEquals(65, majorVersion(input), "Test compilation must target Java 21 too");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"--help", "--version"})
    void packagedRootHelpAndVersionLaunchWithoutAnExternalClasspath(String option) throws Exception {
        Run result = run(temporary, option);
        assertEquals(0, result.exitCode(), result.output());
        if (option.equals("--help")) {
            assertTrue(result.output().contains("Usage: vrn"), result.output());
            for (String command : List.of("init", "add", "commit", "status")) {
                assertTrue(result.output().contains(command), result.output());
            }
        } else {
            assertTrue(result.output().contains("VRN 1.0"), result.output());
        }
    }

    @Test void packagedExecutableCompletesManualWorkflowInADisposableRepository() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        assertSuccess(run(project, "init"));
        assertEquals("NONE", Files.readString(project.resolve(".vrn/HEAD")));
        String contents = "sample content\n";
        Files.writeString(project.resolve("sample.txt"), contents);
        Run initialStatus = run(project, "status");
        assertSuccess(initialStatus);
        assertTrue(initialStatus.output().contains("sample.txt"), initialStatus.output());
        assertSuccess(run(project, "add", "."));
        Run commit = run(project, "commit", "-m", "Packaged executable smoke test");
        assertSuccess(commit);
        assertTrue(commit.output().contains("Committed snapshot"), commit.output());
        String head = Files.readString(project.resolve(".vrn/HEAD"));
        assertTrue(head.matches("[0-9a-f]{32}"));
        assertTrue(Files.isRegularFile(project.resolve(".vrn/snapshots/" + head + ".snapshot")));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(contents, Files.readString(project.resolve(".vrn/objects/" + hash)));
        Run finalStatus = run(project, "status");
        assertSuccess(finalStatus);
        assertTrue(finalStatus.output().contains("Nothing to commit, working directory clean."), finalStatus.output());
        assertFalse(Files.exists(project.resolve(".vrnauto")));
    }

    private static Path artifact() {
        String configured = System.getProperty("vrn.executableJar");
        assertNotNull(configured, "Run Gradle test, which supplies -Dvrn.executableJar for packaging checks");
        Path path = Path.of(configured).toAbsolutePath();
        assertTrue(Files.isRegularFile(path), "Executable artifact is missing: " + path);
        return path;
    }

    private static int majorVersion(InputStream input) throws Exception {
        var data = new DataInputStream(input);
        assertEquals(0xCAFEBABE, data.readInt(), "Not a Java class file");
        assertEquals(0, data.readUnsignedShort(), "Preview bytecode must not be distributed");
        return data.readUnsignedShort();
    }

    private Run run(Path project, String... arguments) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-jar", artifact().toString()));
        command.addAll(List.of(arguments));
        // Logs stay outside the sample project so add/status do not discover them.
        Path output = Files.createTempFile(temporary, "jar-output-", ".txt");
        Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Packaged CLI timed out");
            return new Run(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void assertSuccess(Run result) { assertEquals(0, result.exitCode(), result.output()); }
    private record Run(int exitCode, String output) {}
}
