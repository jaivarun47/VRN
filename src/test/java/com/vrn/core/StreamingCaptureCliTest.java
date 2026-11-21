package com.vrn.core;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java 21 executable with files individually larger than its 64 MiB heap. */
class StreamingCaptureCliTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void largeFilesStageStatusCommitAndReuseUnder64MiBHeap(int count) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        cli(root, "init");
        var expected = new LinkedHashMap<String, String>();
        long size = (count == 1 ? 160L : 80L) * 1024 * 1024;
        for (int i = 0; i < count; i++) {
            String name = "data/file-" + i + ".bin";
            Path source = root.resolve(name);
            Files.createDirectories(source.getParent());
            generate(source, size, 80 + i);
            expected.put(name, independentHash(source));
        }
        String target = count == 1 ? "data/file-0.bin" : ".";
        long started = System.nanoTime();
        cli(root, "add", target);
        System.out.println("Constrained heap: files=" + count + "; bytes/file=" + size + "; heap=64MiB; add.ms=" + (System.nanoTime() - started) / 1_000_000);
        Path storage = root.resolve(".vrn");
        var staged = new StagingArea(storage).readOperations();
        for (var entry : expected.entrySet()) {
            assertEquals(new StagedOperation.Put(entry.getValue()), staged.get(entry.getKey()));
            assertEquals(entry.getValue(), independentHash(storage.resolve("objects/" + entry.getValue())));
        }
        var objectState = new LinkedHashMap<String, Object>();
        for (String hash : expected.values()) objectState.put(hash, Files.getLastModifiedTime(storage.resolve("objects/" + hash)));
        cli(root, "add", target); // Reuse under the same heap limit, with existing PUTs.
        for (var entry : objectState.entrySet()) assertEquals(entry.getValue(), Files.getLastModifiedTime(storage.resolve("objects/" + entry.getKey())));
        cli(root, "status");
        cli(root, "commit", "-m", "large streamed capture");
        String head = Files.readString(storage.resolve("HEAD"));
        assertNotEquals("NONE", head);
        var parent = new SnapshotStore(storage).load(head);
        for (var entry : expected.entrySet()) assertEquals(entry.getValue(), parent.files().get(entry.getKey()));
        Files.write(root.resolve("data/file-0.bin"), new byte[]{1}, StandardOpenOption.APPEND);
        assertTrue(cli(root, "status").contains("file-0.bin"));
        assertEquals(expected.get("data/file-0.bin"), independentHash(storage.resolve("objects/" + expected.get("data/file-0.bin"))));
        cli(root, "add", "data/file-0.bin");
        cli(root, "unstage", "data/file-0.bin");
        assertTrue(new StagingArea(storage).readOperations().isEmpty());
        assertEquals(head, Files.readString(storage.resolve("HEAD")));
    }

    static void generate(Path file, long size, int seed) throws Exception {
        byte[] block = new byte[64 * 1024];
        Random random = new Random(seed);
        try (var output = Files.newOutputStream(file)) {
            for (long remaining = size; remaining > 0;) {
                random.nextBytes(block);
                block[0] = 0;
                int length = (int) Math.min(block.length, remaining);
                output.write(block, 0, length);
                remaining -= length;
            }
        }
    }

    static String independentHash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[32 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            for (int n; (n = input.read(block)) != -1;) digest.update(block, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String cli(Path root, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Xmx64m", "-jar", System.getProperty("vrn.executableJar")));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temporary, "heap-cli-", ".log");
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Constrained-heap CLI timeout");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), String.join(" ", arguments) + ": " + output);
            return output;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
