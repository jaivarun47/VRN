package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.vrn.core.ObjectIntegrityException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class ObjectStoreIntegrityTest {
    @TempDir Path root;
    private Path storage;

    @BeforeEach
    void initialize() throws Exception {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
    }

    static Stream<String> invalidIdentifiers() {
        return Stream.of(null, "", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64),
                "../escape", "a/b", "a\\b", "C:/outside", "/outside", "hash\nforged");
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void identifiersAreRejectedBeforeEvenInspectingNonexistentStorage(String id) throws Exception {
        ObjectStore objects = new ObjectStore(root.resolve("nonexistent"));
        assertKind(MALFORMED_IDENTIFIER, () -> objects.verify(id));
        assertKind(MALFORMED_IDENTIFIER, () -> objects.openVerified(id));
        assertKind(MALFORMED_IDENTIFIER, () -> objects.store(id, new byte[0]));
        assertFalse(Files.exists(root.resolve("nonexistent")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ordinary contents", "Unicode: नमस्ते 🌍"})
    void validObjectsIncludingEmptyFilesAreReadableAndReusable(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String id = digest(bytes);
        ObjectStore objects = new ObjectStore(storage);
        ObjectStore.validateIdentifier(id);
        objects.store(id, bytes);
        objects.store(id, bytes);
        objects.verify(id);
        try (var input = objects.openVerified(id)) { assertArrayEquals(bytes, input.readAllBytes()); }
        assertArrayEquals(bytes, Files.readAllBytes(path(id)));
    }

    @Test
    void missingObjectsHaveAnExplicitFailureCategory() {
        assertKind(MISSING, () -> new ObjectStore(storage).verify("0".repeat(64)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void truncationAndSameLengthSubstitutionFailWithoutReturningAStream(boolean sameLength) throws Exception {
        byte[] bytes = "original".getBytes(StandardCharsets.UTF_8);
        String id = publish(bytes);
        byte[] bad = sameLength ? "modified".getBytes(StandardCharsets.UTF_8) : new byte[0];
        assertEquals(sameLength, bad.length == bytes.length);
        Files.write(path(id), bad);

        assertKind(DIGEST_MISMATCH, () -> new ObjectStore(storage).verify(id));
        assertKind(DIGEST_MISMATCH, () -> new ObjectStore(storage).openVerified(id));
        assertArrayEquals(bad, Files.readAllBytes(path(id)));
    }

    @Test
    void unreadableObjectsAreDetectedWithDeterministicOpenFailure() throws Exception {
        String id = publish("contents".getBytes(StandardCharsets.UTF_8));
        ObjectStore denied = new ObjectStore(storage) {
            @Override protected SeekableByteChannel openObject(Path object) throws IOException {
                throw new AccessDeniedException(object.toString());
            }
        };
        ObjectIntegrityException error = assertKind(UNREADABLE, () -> denied.openVerified(id));
        assertInstanceOf(AccessDeniedException.class, error.getCause());
    }

    @Test
    void incompleteReadClosesItsHandleAndNeverReturnsVerifiedContent() throws Exception {
        String id = publish(new byte[128 * 1024]);
        SeekableByteChannel[] opened = new SeekableByteChannel[1];
        ObjectStore broken = new ObjectStore(storage) {
            @Override protected SeekableByteChannel openObject(Path object) throws IOException {
                opened[0] = new WrappedChannel(super.openObject(object)) {
                    private int reads;
                    @Override public int read(ByteBuffer buffer) throws IOException {
                        if (++reads == 2) throw new IOException("injected read failure");
                        return super.read(buffer);
                    }
                };
                return opened[0];
            }
        };

        assertKind(UNREADABLE, () -> broken.openVerified(id));

        assertFalse(opened[0].isOpen());
    }

    @Test
    void verifierUsesBoundedBuffersRatherThanWholeObjectAllocation() throws Exception {
        byte[] content = new byte[1024 * 1024 + 7];
        Arrays.fill(content, (byte) 42);
        String id = publish(content);
        AtomicInteger largest = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        ObjectStore observed = new ObjectStore(storage) {
            @Override protected SeekableByteChannel openObject(Path object) throws IOException {
                return new WrappedChannel(super.openObject(object)) {
                    @Override public int read(ByteBuffer buffer) throws IOException {
                        largest.accumulateAndGet(buffer.remaining(), Math::max);
                        reads.incrementAndGet();
                        return super.read(buffer);
                    }
                };
            }
        };

        observed.verify(id);

        assertTrue(largest.get() <= 64 * 1024);
        assertTrue(reads.get() > 16);
    }

    @Test
    void repeatedReferencesAreVerifiedOncePerCallButNeverTrustedAcrossCalls() throws Exception {
        byte[] bytes = "contents".getBytes(StandardCharsets.UTF_8);
        String id = publish(bytes);
        AtomicInteger opens = new AtomicInteger();
        ObjectStore counted = new ObjectStore(storage) {
            @Override protected SeekableByteChannel openObject(Path object) throws IOException {
                opens.incrementAndGet();
                return super.openObject(object);
            }
        };
        counted.verifyAll(List.of(id, id, id));
        assertEquals(1, opens.get());
        Files.writeString(path(id), "corrupt");

        assertKind(DIGEST_MISMATCH, () -> counted.verifyAll(List.of(id, id)));

        assertEquals(2, opens.get());
    }

    @Test
    void returnedStreamChecksActualConsumptionAndKeepsFailureSticky() throws Exception {
        String id = publish(new byte[0]);
        try (var input = new ObjectStore(storage).openVerified(id)) {
            Files.writeString(path(id), "externally changed after preverification");
            assertKind(DIGEST_MISMATCH, input::readAllBytes);
            assertKind(DIGEST_MISMATCH, input::read);
        }
    }

    @Test
    void successfulVerifiedEofDoesNotExposeBytesAppendedLater() throws Exception {
        byte[] bytes = "original".getBytes(StandardCharsets.UTF_8);
        String id = publish(bytes);
        try (var input = new ObjectStore(storage).openVerified(id)) {
            assertArrayEquals(bytes, input.readAllBytes());
            Files.writeString(path(id), "later append", java.nio.file.StandardOpenOption.APPEND);
            assertEquals(-1, input.read());
            assertEquals(0, input.read(new byte[0]));
            assertEquals(0, input.skip(10));
        }
    }

    @Test
    void skippingStillParticipatesInTheEofDigest() throws Exception {
        byte[] bytes = "bytes for skipping".getBytes(StandardCharsets.UTF_8);
        String id = publish(bytes);
        try (var input = new ObjectStore(storage).openVerified(id)) {
            assertEquals(5, input.skip(5));
            assertArrayEquals(Arrays.copyOfRange(bytes, 5, bytes.length), input.readAllBytes());
        }
    }

    @Test
    void publicationFailureDoesNotPublishAnObjectOrLeaveTemporaryFiles() throws Exception {
        byte[] bytes = "publish me".getBytes(StandardCharsets.UTF_8);
        String id = digest(bytes);
        AtomicFilePublisher broken = new AtomicFilePublisher() {
            @Override protected void write(Path temporary, byte[] contents) throws IOException {
                Files.writeString(temporary, "partial");
                throw new IOException("injected object write failure");
            }
        };

        assertKind(PUBLICATION_FAILED, () -> new ObjectStore(storage, broken).store(id, bytes));

        assertFalse(Files.exists(path(id)));
        try (var entries = Files.list(storage.resolve("objects"))) { assertEquals(0, entries.count()); }
    }

    @Test
    void defectivePublicationIsDetectedBeforeStoreReportsSuccess_withoutAutomaticRepair() throws Exception {
        byte[] bytes = "publish me".getBytes(StandardCharsets.UTF_8);
        String id = digest(bytes);
        AtomicFilePublisher broken = new AtomicFilePublisher() {
            @Override protected void write(Path temporary, byte[] contents) throws IOException {
                Files.writeString(temporary, "wrong bytes");
            }
        };

        assertKind(DIGEST_MISMATCH, () -> new ObjectStore(storage, broken).store(id, bytes));
        assertKind(DIGEST_MISMATCH, () -> new ObjectStore(storage).store(id, bytes));

        assertEquals("wrong bytes", Files.readString(path(id)));
        assertFalse(Files.exists(storage.resolve("staging/index")));
    }

    @Test
    void metadataInspectionDoesNotImplicitlyHashObjectsOrRepairHistory() throws Exception {
        Snapshot metadata = new Snapshot("inspection", "NONE", 1, "metadata only", java.util.Map.of("file.txt", "legacy-token"));
        SnapshotStore snapshots = new SnapshotStore(storage);
        snapshots.save(metadata);
        byte[] raw = Files.readAllBytes(storage.resolve("snapshots/inspection.snapshot"));

        assertEquals(metadata, snapshots.load("inspection"));
        assertKind(MALFORMED_IDENTIFIER, () -> new ObjectStore(storage).verifyAll(metadata.files().values()));

        assertArrayEquals(raw, Files.readAllBytes(storage.resolve("snapshots/inspection.snapshot")));
    }

    private String publish(byte[] bytes) throws Exception {
        String id = digest(bytes);
        new ObjectStore(storage).store(id, bytes);
        return id;
    }
    private Path path(String id) { return storage.resolve("objects").resolve(id); }
    private String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private ObjectIntegrityException assertKind(ObjectIntegrityException.Kind expected,
                                                org.junit.jupiter.api.function.Executable operation) {
        ObjectIntegrityException error = assertThrows(ObjectIntegrityException.class, operation);
        assertEquals(expected, error.kind(), error.getMessage());
        return error;
    }

    private static class WrappedChannel implements SeekableByteChannel {
        protected final SeekableByteChannel delegate;
        WrappedChannel(SeekableByteChannel delegate) { this.delegate = delegate; }
        public int read(ByteBuffer buffer) throws IOException { return delegate.read(buffer); }
        public int write(ByteBuffer buffer) throws IOException { return delegate.write(buffer); }
        public long position() throws IOException { return delegate.position(); }
        public SeekableByteChannel position(long value) throws IOException { delegate.position(value); return this; }
        public long size() throws IOException { return delegate.size(); }
        public SeekableByteChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
        public boolean isOpen() { return delegate.isOpen(); }
        public void close() throws IOException { delegate.close(); }
    }
}
