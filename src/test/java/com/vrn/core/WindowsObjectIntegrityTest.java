package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsObjectIntegrityTest {
    @TempDir Path root;
    private Path storage;
    private String id;
    private byte[] bytes;

    @BeforeEach
    void initialize() throws Exception {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        bytes = "Windows object contents".getBytes(StandardCharsets.UTF_8);
        id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @ParameterizedTest
    @ValueSource(strings = {"storage", "objects", "leaf"})
    void junctionSubstitutionsAreRejectedWithoutChangingTheirTargets(String component) throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.write(outside.resolve(id), bytes);
        Path link;
        Path target = outside;
        Path selected = storage;
        if (component.equals("storage")) {
            link = root.resolve("alias-storage");
            target = storage;
            Files.write(storage.resolve("objects").resolve(id), bytes);
            selected = link;
        } else if (component.equals("objects")) {
            link = storage.resolve("objects");
            Files.delete(link); // empty test directory only
        } else {
            link = storage.resolve("objects").resolve(id);
        }
        Run created = run("cmd.exe", "/d", "/c", "mklink /J \"" + link + "\" \"" + target + "\"");
        assumeTrue(created.code() == 0, "Junction unavailable: " + created.output());
        try {
            ObjectStore objects = new ObjectStore(selected);
            assertUnsafe(() -> objects.verify(id));
            assertUnsafe(() -> objects.openVerified(id));
            assertUnsafe(() -> objects.store(id, bytes));
            assertArrayEquals(bytes, Files.readAllBytes(outside.resolve(id)));
            if (component.equals("storage")) {
                assertFalse(Files.exists(storage.resolve("mutation.lock")), "Unsafe root must be rejected before creating a lock");
            }
        } finally {
            Files.deleteIfExists(link); // unlink only, never traverse/delete the target tree
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void symbolicObjectOrObjectDirectoryIsRejectedWherePrivilegesPermit(boolean directory) throws Exception {
        Path outside = directory ? Files.createDirectory(root.resolve("outside")) : root.resolve("outside-file");
        if (directory) Files.write(outside.resolve(id), bytes); else Files.write(outside, bytes);
        Path link = directory ? storage.resolve("objects") : storage.resolve("objects").resolve(id);
        if (directory) Files.delete(link);
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symbolic link privilege unavailable: " + unavailable.getMessage());
        }
        try {
            ObjectStore objects = new ObjectStore(storage);
            assertUnsafe(() -> objects.verify(id));
            assertUnsafe(() -> objects.store(id, bytes));
            assertArrayEquals(bytes, Files.readAllBytes(directory ? outside.resolve(id) : outside));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void hardLinkContentIsVerifiedAgainAfterAnExternalAliasMutation() throws Exception {
        Path outside = Files.write(root.resolve("hard-link-source"), bytes);
        Path object = storage.resolve("objects").resolve(id);
        try {
            Files.createLink(object, outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Hard links unavailable: " + unavailable.getMessage());
        }
        ObjectStore objects = new ObjectStore(storage);
        objects.verify(id);
        Files.writeString(outside, "changed through another directory entry");

        ObjectIntegrityException error = assertThrows(ObjectIntegrityException.class, () -> objects.store(id, bytes));

        assertEquals(ObjectIntegrityException.Kind.DIGEST_MISMATCH, error.kind());
        assertEquals("changed through another directory entry", Files.readString(outside));
        assertEquals("changed through another directory entry", Files.readString(object));
    }

    private void assertUnsafe(org.junit.jupiter.api.function.Executable action) {
        ObjectIntegrityException error = assertThrows(ObjectIntegrityException.class, action);
        assertEquals(ObjectIntegrityException.Kind.UNSAFE_PATH, error.kind(), error.getMessage());
    }

    private Run run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Windows experiment timed out");
            return new Run(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private record Run(int code, String output) { }
}
