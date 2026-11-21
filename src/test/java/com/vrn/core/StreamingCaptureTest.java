package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StreamingCaptureTest {
    @TempDir Path temporary;
    Path root;
    Path storage;
    Path index;
    byte[] baseline;
    String head;
    Map<String, String> history;

    @BeforeEach void prepare() throws Exception {
        root = Files.createDirectory(temporary.resolve("project"));
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
        index = storage.resolve("staging/index");
        Files.writeString(root.resolve("old.txt"), "old tracked data");
        Files.writeString(root.resolve("keep.txt"), "inherited data");
        new AddService(root).add(Path.of("."));
        new CommitService(storage).commit("parent");
        new AddService(root).add(Path.of("keep.txt"));
        new StagingArea(storage).stageDelete(Path.of("old.txt"));
        baseline = Files.readAllBytes(index);
        head = Files.readString(storage.resolve("HEAD"));
        history = RepositoryPreflightTest.tree(storage.resolve("snapshots"));
        Files.createDirectory(root.resolve("data"));
    }

    @ParameterizedTest @ValueSource(longs = {0, 1, 8191, 65536, 65537, 200003, 1048576})
    void independentDigestAndStoredBytesMatchAcrossBufferBoundaries(long size) throws Exception {
        Path source = data("binary.bin", size);
        String expected = StreamingCaptureCliTest.independentHash(source);
        assertEquals(1, new AddService(root).add(Path.of("data")).selectedFiles());
        Path object = storage.resolve("objects/" + expected);
        assertEquals(size, Files.size(object));
        assertEquals(expected, StreamingCaptureCliTest.independentHash(object));
        assertEquals(-1, Files.mismatch(source, object));
        assertEquals(new StagedOperation.Put(expected), new StagingArea(storage).readOperations().get("data/binary.bin"));
        unchangedHistory();
        noTemporaryObjects();
    }

    @Test void unicodeContentsAndSpecialPathsRemainLossless() throws Exception {
        String text = "日本語\u0000 café \uD83D\uDE80\n";
        Files.writeString(root.resolve("data/config=production.txt"), text);
        Files.writeString(root.resolve("data/#notes.txt"), text);
        new AddService(root).add(Path.of("data"));
        String hash = StatusCorrectnessTest.digest(text);
        assertEquals(text, Files.readString(storage.resolve("objects/" + hash)));
        for (String path : new String[]{"data/config=production.txt", "data/#notes.txt"}) {
            assertEquals(new StagedOperation.Put(hash), new StagingArea(storage).readOperations().get(path));
        }
    }

    @Test void identicalFilesAndRepeatedAddReuseExistingObjectWithoutReplacement() throws Exception {
        Path source = data("one.bin", 200003);
        Files.copy(source, root.resolve("data/two.bin"));
        String hash = StreamingCaptureCliTest.independentHash(source);
        new AddService(root).add(Path.of("data"));
        Path object = storage.resolve("objects/" + hash);
        var before = Files.readAttributes(object, BasicFileAttributes.class);
        new AddService(root).add(Path.of("data"));
        var after = Files.readAttributes(object, BasicFileAttributes.class);
        assertEquals(before.fileKey(), after.fileKey());
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
        assertEquals(2, new StagingArea(storage).readOperations().keySet().stream().filter(path -> path.startsWith("data/")).count());
        noTemporaryObjects();
    }

    @Test void allFilesReuseOneFixedCaptureBufferAndGuard() throws Exception {
        data("one.bin", 200003);
        data("two.bin", 200003);
        var first = new AtomicReference<byte[]>();
        var reads = new AtomicInteger();
        ContentCapture capture = new ContentCapture() {
            @Override protected int read(InputStream input, byte[] bytes) throws IOException {
                assertEquals(64 * 1024, bytes.length);
                if (first.get() == null) first.set(bytes);
                else assertSame(first.get(), bytes);
                assertThrows(IOException.class, () -> RepositoryMutationGuard.acquire(storage));
                reads.incrementAndGet();
                return super.read(input, bytes);
            }
        };
        add(capture, new AtomicFilePublisher());
        assertTrue(reads.get() >= 8);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void midStreamFailureAtAnyBatchPositionPreservesMixedRawIndex(int failAt) throws Exception {
        for (int i = 0; i < 3; i++) data(i + ".bin", 200003 + i);
        var completed = new AtomicInteger();
        ContentCapture capture = new ContentCapture() {
            int current = -1;
            int reads;
            @Override String capture(Path project, SelectedFile file, Path temp) throws Exception {
                current++;
                reads = 0;
                String hash = super.capture(project, file, temp);
                completed.incrementAndGet();
                return hash;
            }
            @Override protected int read(InputStream input, byte[] bytes) throws IOException {
                if (current == failAt && ++reads == 2) throw new IOException("injected middle-of-stream read failure");
                return super.read(input, bytes);
            }
        };
        failed(capture, new AtomicFilePublisher());
        assertEquals(failAt, completed.get());
        try (var objects = Files.list(storage.resolve("objects"))) {
            for (Path object : objects.toList()) new ObjectStore(storage).verify(object.getFileName().toString());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"removed", "replaced", "directory", "truncate", "append", "same-size"})
    void detectableSourceChangesFailSafely(String kind) throws Exception {
        Path source = data("source.bin", 200003);
        ContentCapture capture = new ContentCapture() {
            boolean changed;
            @Override protected InputStream openSource(Path file) throws IOException {
                if (kind.equals("removed")) Files.delete(file);
                if (kind.equals("directory")) { Files.delete(file); Files.createDirectory(file); }
                if (kind.equals("replaced")) {
                    Files.move(file, file.resolveSibling("original-saved.bin"));
                    Files.write(file, new byte[200003]);
                }
                return super.openSource(file);
            }
            @Override protected int read(InputStream input, byte[] bytes) throws IOException {
                int n = super.read(input, bytes);
                if (!changed && n > 0) {
                    changed = true;
                    if (kind.equals("truncate")) Files.newByteChannel(source, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).close();
                    if (kind.equals("append")) Files.write(source, new byte[]{1}, StandardOpenOption.APPEND);
                    if (kind.equals("same-size")) {
                        long previous = Files.getLastModifiedTime(source).toMillis();
                        try (var writer = Files.newByteChannel(source, StandardOpenOption.WRITE)) { writer.write(java.nio.ByteBuffer.wrap(new byte[]{42})); }
                        Files.setLastModifiedTime(source, FileTime.fromMillis(previous + 5000));
                    }
                }
                return n;
            }
        };
        failed(capture, new AtomicFilePublisher());
    }

    @Test void leafSymlinkSubstitutionIsRejectedWhenPrivilegesPermit() throws Exception {
        Path source = data("source.bin", 200003);
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "outside unchanged");
        Path probe = temporary.resolve("link-probe");
        try { Files.createSymbolicLink(probe, outside); Files.delete(probe); }
        catch (IOException | UnsupportedOperationException failure) { assumeTrue(false, "Symbolic link privilege unavailable: " + failure.getMessage()); }
        ContentCapture capture = new ContentCapture() {
            @Override protected InputStream openSource(Path file) throws IOException {
                Files.delete(source);
                Files.createSymbolicLink(source, outside);
                return super.openSource(file);
            }
        };
        failed(capture, new AtomicFilePublisher());
        assertEquals("outside unchanged", Files.readString(outside));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void temporaryWriteOrCloseFailureCleansUpAndClosesSource(boolean closeFailure) throws Exception {
        data("source.bin", 200003);
        var closed = new AtomicBoolean();
        ContentCapture capture = new ContentCapture() {
            @Override protected InputStream openSource(Path source) throws IOException {
                return new FilterInputStream(super.openSource(source)) {
                    @Override public void close() throws IOException {
                        super.close();
                        closed.set(true);
                        if (closeFailure) throw new IOException("injected close failure");
                    }
                };
            }
            @Override protected void write(OutputStream output, byte[] bytes, int length) throws IOException {
                output.write(bytes, 0, Math.min(7, length));
                if (!closeFailure) throw new IOException("injected temporary write failure");
                output.write(bytes, Math.min(7, length), length - Math.min(7, length));
            }
        };
        for (int i = 0; i < 3; i++) {
            closed.set(false);
            failed(capture, new AtomicFilePublisher());
            assertTrue(closed.get());
        }
    }

    @Test void parentJunctionSubstitutionIsRejectedBeforeReadingOutsideData() throws Exception {
        data("source.bin", 200003);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("source.bin"), "outside unchanged");
        Path probe = temporary.resolve("junction-probe");
        WindowsMetadataBoundaryTest.junction(probe, outside);
        Files.delete(probe);
        ContentCapture capture = new ContentCapture() {
            @Override protected InputStream openSource(Path file) throws IOException {
                Files.move(root.resolve("data"), root.resolve("saved-data"));
                try { WindowsMetadataBoundaryTest.junction(root.resolve("data"), outside); }
                catch (Exception failure) { throw new IOException("Junction fixture failed", failure); }
                return super.openSource(file);
            }
        };
        try { failed(capture, new AtomicFilePublisher()); }
        finally { if (Files.readAttributes(root.resolve("data"), BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther()) Files.delete(root.resolve("data")); }
        assertEquals("outside unchanged", Files.readString(outside.resolve("source.bin")));
    }

    @Test void preparedObjectCorruptionCannotBePublished() throws Exception {
        Path source = data("source.bin", 200003);
        String hash = StreamingCaptureCliTest.independentHash(source);
        ContentCapture capture = new ContentCapture() {
            @Override protected void write(OutputStream output, byte[] bytes, int length) throws IOException {
                output.write(bytes[0] ^ 1);
                output.write(bytes, 1, length - 1);
            }
        };
        failed(capture, new AtomicFilePublisher());
        assertFalse(Files.exists(storage.resolve("objects/" + hash)));
    }

    @Test void corruptExistingObjectRemainsUntouchedAndRejectsBatch() throws Exception {
        Path source = data("source.bin", 200003);
        String hash = StreamingCaptureCliTest.independentHash(source);
        Path object = storage.resolve("objects/" + hash);
        Files.writeString(object, "corrupt existing object");
        failed(new ContentCapture(), new AtomicFilePublisher());
        assertEquals("corrupt existing object", Files.readString(object));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void publicationCollisionVerifiesWinnerWithoutOverwriting(boolean corrupt) throws Exception {
        Path source = data("source.bin", 200003);
        String hash = StreamingCaptureCliTest.independentHash(source);
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override protected void linkObject(Path temp, Path destination) throws IOException {
                if (corrupt) Files.writeString(destination, "foreign corrupt object");
                else Files.copy(temp, destination);
                super.linkObject(temp, destination); // Must throw FileAlreadyExistsException.
            }
        };
        if (corrupt) {
            failed(new ContentCapture(), publisher);
            assertEquals("foreign corrupt object", Files.readString(storage.resolve("objects/" + hash)));
        } else {
            add(new ContentCapture(), publisher);
            new ObjectStore(storage).verify(hash);
        }
        noTemporaryObjects();
    }

    @ParameterizedTest @ValueSource(strings = {"before", "after", "unsupported"})
    void objectPublicationFailuresNeverPublishIndexOrIncompleteFinalObject(String point) throws Exception {
        Path source = data("source.bin", 200003);
        String hash = StreamingCaptureCliTest.independentHash(source);
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override protected void linkObject(Path temp, Path destination) throws IOException {
                if (point.equals("unsupported")) throw new UnsupportedOperationException("injected unsupported hard links");
                if (point.equals("before")) throw new IOException("injected publication failure");
                super.linkObject(temp, destination);
                throw new IOException("injected failure after object publication");
            }
        };
        failed(new ContentCapture(), publisher);
        if (point.equals("after")) new ObjectStore(storage).verify(hash);
        else assertFalse(Files.exists(storage.resolve("objects/" + hash)));
    }

    @Test void cleanupFailureLeavesVerifiedObjectAndExplicitlyAbortsIndex() throws Exception {
        Path source = data("source.bin", 200003);
        String hash = StreamingCaptureCliTest.independentHash(source);
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override public void delete(Path path) throws IOException {
                throw new IOException("injected object temporary cleanup failure");
            }
        };
        assertThrows(IOException.class, () -> add(new ContentCapture(), publisher));
        assertArrayEquals(baseline, Files.readAllBytes(index));
        new ObjectStore(storage).verify(hash);
        try (var entries = Files.list(storage.resolve("objects"))) {
            for (Path entry : entries.filter(path -> path.getFileName().toString().startsWith(".capture-")).toList()) Files.delete(entry);
        }
        noTemporaryObjects();
    }

    @Test void invalidSelectedDestinationAbortsBeforeStreaming() throws Exception {
        data("source.bin", 200003);
        assertThrows(Exception.class, () -> new AddService(root).add(Path.of("../outside")));
        assertArrayEquals(baseline, Files.readAllBytes(index));
        noTemporaryObjects();
    }

    private Path data(String name, long size) throws Exception {
        Path file = root.resolve("data/" + name);
        StreamingCaptureCliTest.generate(file, size, name.hashCode());
        return file;
    }
    private void add(ContentCapture capture, AtomicFilePublisher publisher) throws Exception {
        var store = new ObjectStore(storage, publisher, capture);
        new AddService(root, new FileScanner(), store, new AtomicFilePublisher()).add(Path.of("data"));
    }
    private void failed(ContentCapture capture, AtomicFilePublisher publisher) throws Exception {
        Exception failure = assertThrows(Exception.class, () -> add(capture, publisher));
        assertTrue(failure.getMessage().contains("no partial staging-index update was published"), failure.getMessage());
        assertArrayEquals(baseline, Files.readAllBytes(index));
        unchangedHistory();
        noTemporaryObjects();
    }
    private void unchangedHistory() throws Exception {
        assertEquals(head, Files.readString(storage.resolve("HEAD")));
        assertEquals(history, RepositoryPreflightTest.tree(storage.resolve("snapshots")));
    }
    private void noTemporaryObjects() throws Exception {
        try (var entries = Files.list(storage.resolve("objects"))) {
            assertTrue(entries.allMatch(path -> path.getFileName().toString().matches("[0-9a-f]{64}")), "Incomplete temporary object was not cleaned up");
        }
    }
}
