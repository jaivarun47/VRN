package com.vrn.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

import static com.vrn.core.ObjectIntegrityException.Kind.*;

/** Content-addressed storage at one supplied location; integrity checks never repair objects. */
public class ObjectStore {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final Path objectsDirectory;
    private final Path storage;
    private final AtomicFilePublisher files;

    public ObjectStore(Path storage) {
        this(storage, new AtomicFilePublisher());
    }

    ObjectStore(Path storage, AtomicFilePublisher files) {
        this.storage = storage.toAbsolutePath().normalize();
        this.objectsDirectory = this.storage.resolve("objects");
        this.files = files;
    }

    public static void validateIdentifier(String id) throws ObjectIntegrityException {
        if (id == null || id.length() != 64 || !id.matches("[0-9a-f]{64}")) {
            throw failure(MALFORMED_IDENTIFIER, id,
                    "Malformed object identifier; expected exactly 64 lowercase hexadecimal SHA-256 characters.", null);
        }
    }

    /** Validate input and observed existing objects before reuse; never deliberately replace an existing object. */
    public void store(String id, byte[] content) throws Exception {
        validateIdentifier(id);
        if (content == null) throw new IllegalArgumentException("Object content is required");
        checkDigest(id, digest().digest(content), "Incoming content does not match the supplied object identifier.");
        requireSafeStorage();
        objectPath(id); // Preserve object-specific unsafe-path diagnostics before generic guarded preflight.
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            Path object = objectPath(id);
            try {
                Files.readAttributes(object, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException absent) {
                try {
                    files.replace(object, content);
                } catch (IOException | RuntimeException publicationFailure) {
                    throw failure(PUBLICATION_FAILED, id,
                            "Object write/publication failed; no automatic repair was attempted.", publicationFailure);
                }
                // Also detect an interrupted/mutating caller buffer or defective publication before reporting success.
                verify(id);
                return;
            } catch (IOException unreadable) {
                throw failure(UNREADABLE, id, "Existing object attributes cannot be read.", unreadable);
            }
            verify(id);
        }
    }

    /** One streaming digest pass. No object bytes or successful result are cached across calls. */
    public void verify(String id) throws IOException {
        try (SeekableByteChannel channel = verifiedChannel(id)) {
            // verifiedChannel has read the complete object before returning.
        }
    }

    /** Each distinct reference is hashed once during this call; external writers remain outside the guard. */
    public void verifyAll(Iterable<String> references) throws IOException {
        var unique = new LinkedHashSet<String>();
        for (String id : references) {
            validateIdentifier(id); // validate every identifier before any is used as a path
            unique.add(id);
        }
        for (String id : unique) verify(id);
    }

    /**
     * Verifies before exposing bytes, then reads from the same open handle. A second
     * digest is checked at EOF against the bytes actually consumed. Close the stream;
     * read through EOF for this second check. No full-file memory buffer is created.
     * This is not a filesystem snapshot: hostile in-place writes can still race reads.
     */
    public InputStream openVerified(String id) throws IOException {
        SeekableByteChannel channel = verifiedChannel(id);
        try {
            channel.position(0);
            return new VerifiedInput(Channels.newInputStream(channel), id);
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    // Narrow fault-injection seam; production always opens a non-following, read-only handle.
    protected SeekableByteChannel openObject(Path object) throws IOException {
        return Files.newByteChannel(object, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    void requireSafeStorage() throws IOException {
        requireDirectory(storage, null);
    }

    private SeekableByteChannel verifiedChannel(String id) throws IOException {
        validateIdentifier(id);
        Path object = objectPath(id);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(object, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            throw failure(MISSING, id, "Referenced object is missing.", absent);
        } catch (IOException denied) {
            throw failure(UNREADABLE, id, "Object attributes cannot be read.", denied);
        }
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw failure(UNSAFE_PATH, id, "Symbolic/reparse object entries are not supported.", null);
        }
        if (!attributes.isRegularFile()) {
            throw failure(UNEXPECTED_TYPE, id, "Expected a regular content-object file.", null);
        }
        SeekableByteChannel channel;
        try {
            channel = openObject(object);
        } catch (NoSuchFileException absent) {
            throw failure(MISSING, id, "Object disappeared before it could be opened.", absent);
        } catch (UnsupportedOperationException unsupported) {
            throw failure(UNSAFE_PATH, id, "Provider cannot open the object without following links.", unsupported);
        } catch (IOException denied) {
            throw failure(UNREADABLE, id, "Object cannot be opened for reading.", denied);
        }
        try {
            MessageDigest hash = digest();
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                hash.update(buffer);
                buffer.clear();
            }
            checkDigest(id, hash.digest(), "Stored content does not match its digest (possible truncation or substitution).");
            return channel;
        } catch (IOException | RuntimeException readFailure) {
            try { channel.close(); } catch (IOException closeFailure) { readFailure.addSuppressed(closeFailure); }
            if (readFailure instanceof ObjectIntegrityException integrity) throw integrity;
            throw failure(UNREADABLE, id, "Complete object contents could not be read.", readFailure);
        }
    }

    private Path objectPath(String id) throws IOException {
        requireDirectory(storage, id);
        requireDirectory(objectsDirectory, id);
        Path realStorage;
        Path realObjects;
        try {
            realStorage = storage.toRealPath();
            realObjects = objectsDirectory.toRealPath();
        } catch (IOException unreadable) {
            throw failure(UNREADABLE, id, "Object-store location cannot be resolved safely.", unreadable);
        }
        if (!realObjects.getParent().toString().equals(realStorage.toString())) {
            throw failure(UNSAFE_PATH, id, "Resolved object directory is outside the supplied storage directory.", null);
        }
        // id was checked before reaching this method; no separators or namespace syntax can enter resolve().
        return objectsDirectory.resolve(id);
    }

    private static void requireDirectory(Path directory, String id) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            throw failure(MISSING, id, "Required storage directory is missing: " + directory, absent);
        } catch (IOException denied) {
            throw failure(UNREADABLE, id, "Storage directory cannot be inspected: " + directory, denied);
        }
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw failure(UNSAFE_PATH, id, "Symbolic/reparse storage directories are not supported: " + directory, null);
        }
        if (!attributes.isDirectory()) {
            throw failure(UNEXPECTED_TYPE, id, "Required storage path is not a directory: " + directory, null);
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Java requires SHA-256", unavailable); }
    }

    private static void checkDigest(String id, byte[] actual, String reason) throws ObjectIntegrityException {
        String observed = HexFormat.of().formatHex(actual);
        if (!id.equals(observed)) throw failure(DIGEST_MISMATCH, id, reason + " Actual SHA-256: " + observed, null);
    }

    private static ObjectIntegrityException failure(ObjectIntegrityException.Kind kind, String id, String reason, Throwable cause) {
        return new ObjectIntegrityException(kind, id, reason, cause);
    }

    private static final class VerifiedInput extends InputStream {
        private final InputStream input;
        private final String id;
        private final MessageDigest hash = digest();
        private final byte[] single = new byte[1];
        private boolean complete;
        private IOException terminalFailure;

        private VerifiedInput(InputStream input, String id) { this.input = input; this.id = id; }

        @Override public int read() throws IOException {
            int count = read(single, 0, 1);
            return count == -1 ? -1 : single[0] & 0xff;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (terminalFailure != null) throw terminalFailure;
            if (complete) return -1; // a verified object stream must not expose later appends after its EOF
            int count;
            try { count = input.read(bytes, offset, length); }
            catch (IOException unreadable) {
                terminalFailure = failure(UNREADABLE, id, "Verified object read failed.", unreadable);
                throw terminalFailure;
            }
            if (count > 0) hash.update(bytes, offset, count);
            if (count == -1 && !complete) {
                try {
                    checkDigest(id, hash.digest(), "Bytes consumed after verification do not match the expected digest.");
                    complete = true;
                } catch (ObjectIntegrityException mismatch) {
                    terminalFailure = mismatch;
                    throw mismatch;
                }
            }
            return count;
        }

        @Override public void close() throws IOException { input.close(); }
    }
}
