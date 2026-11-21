package com.vrn.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.*;
import static com.vrn.core.RestoreJournal.*;

/** Windows NTFS primitives. Working destinations are NEVER passed to ATOMIC_MOVE/REPLACE_EXISTING. */
class RestoreFileOps extends AtomicFilePublisher {
    enum Event { BACKUP_COPY, BACKUP_VERIFY, TARGET_PREPARE, TARGET_VERIFY, JOURNAL_WRITE,
        BEFORE_STEP, AFTER_STEP, VERIFY_RESULT, BEFORE_COMPLETE, ROLLBACK_STEP, AFTER_ROLLBACK_STEP, CLEANUP }
    static final List<String> BUCKETS = List.of("backup", "prepared", "original-files", "directories", "undo");
    final Path root;
    final Path storage;

    RestoreFileOps(Path root) { this.root = root.toAbsolutePath().normalize(); this.storage = this.root.resolve(".vrn"); }
    protected void checkpoint(Event event, String detail) throws IOException {}

    void qualified() throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows") || !Files.getFileStore(root).type().equalsIgnoreCase("NTFS")) {
            throw new IOException("Working-tree restoration is currently qualified only on Windows NTFS");
        }
        sameVolume(root, storage);
    }

    void sameVolume(Path source, Path destinationParent) throws IOException {
        if (!Files.getFileStore(source).equals(Files.getFileStore(destinationParent))) {
            throw new IOException("Restoration requires same-volume NTFS operations; cross-volume fallback is disabled");
        }
    }

    Path asset(String bucket, int id) throws IOException {
        if (!BUCKETS.contains(bucket) || id < 0) throw new IOException("Invalid restore asset reference");
        Path parent = new MetadataPaths(storage).directory("restore-work/" + bucket);
        Path path = parent.resolve(Integer.toString(id));
        BasicFileAttributes attrs = attributes(path);
        if (attrs != null) {
            if (attrs.isSymbolicLink() || attrs.isOther() || (bucket.equals("directories") ? !attrs.isDirectory() : !attrs.isRegularFile())) {
                throw new IOException("Unsafe restoration asset type: " + bucket + "/" + id);
            }
            if (bucket.equals("directories")) new MetadataPaths(storage).directory("restore-work/" + bucket + "/" + id);
        }
        return path;
    }

    static BasicFileAttributes attributes(Path path) throws IOException {
        try { return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return null; }
    }

    WorkingTreePaths.Probe probe(String path) throws IOException {
        var probes = new WorkingTreePaths(root).observeDestination(path);
        var result = probes.getLast();
        if (result.problem() != null) throw new IOException(result.problem());
        if (!result.actual().equals(path)) throw new IOException("Working path spelling changed: " + path + " -> " + result.actual());
        return result;
    }

    Path work(String path) throws IOException { SnapshotManifestValidator.validatePath(path); return root.resolve(path); }

    void parents(String path) throws IOException {
        SnapshotManifestValidator.validatePath(path);
        int separator = path.lastIndexOf('/');
        Path parent = root;
        if (separator >= 0) {
            String prefix = path.substring(0, separator);
            String actual = new WorkingTreePaths(root).directoryForTransition(prefix).toString().replace('\\', '/');
            if (!actual.equals(prefix)) throw new IOException("Unexpected parent spelling: " + prefix);
            parent = root.resolve(prefix);
        }
        sameVolume(storage, parent);
    }

    void root(Stamp expected) throws IOException {
        var attrs = attributes(root);
        if (attrs == null || !attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()
                || !expected.matches(attrs, true) || !root.toRealPath().toString().equals(root.toString())) {
            throw new IOException("Restoration project root identity changed");
        }
    }

    boolean missing(String path) throws IOException { return probe(path).attributes() == null; }

    boolean file(String path, Item item, Path linkedTo) throws Exception {
        var probe = probe(path);
        var attrs = probe.attributes();
        if (attrs == null || !attrs.isRegularFile() || !item.stamp().matches(attrs, false)) return false;
        parents(path);
        if (!new FileHasher().hash(work(path)).equals(item.hash())) return false;
        var after = probe(path).attributes();
        return after != null && item.stamp().matches(after, false)
                && (linkedTo == null || Files.isSameFile(work(path), linkedTo));
    }

    boolean directory(String path, Stamp stamp) throws IOException {
        var attrs = probe(path).attributes();
        if (attrs == null || !attrs.isDirectory() || !stamp.matches(attrs, true)) return false;
        parents(path); return empty(work(path));
    }

    boolean privateFile(String bucket, Item item) throws Exception {
        Path path = asset(bucket, item.id());
        var attrs = attributes(path);
        return attrs != null && item.stamp().matches(attrs, false) && new FileHasher().hash(path).equals(item.hash());
    }

    void verifyHash(Path file, String hash) throws Exception {
        var attrs = attributes(file);
        if (attrs == null || !attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()
                || !new FileHasher().hash(file).equals(hash)) throw new IOException("Missing, unsafe or corrupt restoration copy: " + file.getFileName());
    }

    boolean privateDirectory(Directory dir) throws IOException {
        Path path = asset("directories", dir.id());
        var attrs = attributes(path);
        return attrs != null && dir.stamp().matches(attrs, true) && empty(path);
    }

    static boolean empty(Path directory) throws IOException {
        try (var entries = Files.newDirectoryStream(directory)) { return !entries.iterator().hasNext(); }
    }

    void noReadOnly(Path path) throws IOException {
        if (Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isReadOnly()) {
            throw new IOException("Read-only working entries are not supported by restoration: " + path);
        }
    }

    void rename(Path source, Path destination) throws IOException {
        sameVolume(source, destination.getParent());
        // JDK's Windows same-volume path is MoveFileEx(...,0), preserving ACLs/identity.
        // ATOMIC_MOVE instead sets REPLACE_EXISTING and is deliberately NOT used here.
        Files.move(source, destination);
    }

    void link(Path destination, Path original) throws IOException {
        sameVolume(original, destination.getParent());
        Files.createLink(destination, original); // Explicit fail-if-present, complete content only.
    }

    void backup(Item item) throws Exception {
        checkpoint(Event.BACKUP_COPY, item.path());
        parents(item.path());
        if (!file(item.path(), item, null)) throw new IOException("Original changed before backup: " + item.path());
        noReadOnly(work(item.path()));
        try (var channel = Files.newByteChannel(work(item.path()), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) {
            copy(input, asset("backup", item.id()));
        }
        checkpoint(Event.BACKUP_VERIFY, item.path());
        verifyHash(asset("backup", item.id()), item.hash());
        if (!file(item.path(), item, null)) throw new IOException("Original changed during backup: " + item.path());
    }

    Item target(Item item, Item original) throws Exception {
        checkpoint(Event.TARGET_PREPARE, item.path());
        Path target = asset("prepared", item.id());
        try (InputStream input = new ObjectStore(storage).openVerified(item.hash())) { copy(input, target); }
        if (original != null) {
            parents(original.path());
            var attrs = probe(original.path()).attributes();
            if (attrs == null || !attrs.isRegularFile()) throw new IOException("Original changed while preserving file attributes");
            noReadOnly(work(original.path()));
            var from = Files.getFileAttributeView(work(original.path()), java.nio.file.attribute.AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            var to = Files.getFileAttributeView(target, java.nio.file.attribute.AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (from == null || to == null) throw new IOException("Cannot preserve replacement-file ACLs on this provider");
            to.setAcl(from.getAcl());
            if (!to.getOwner().equals(from.getOwner())) to.setOwner(from.getOwner());
            var sourceDos = Files.readAttributes(work(original.path()), DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var targetDos = Files.getFileAttributeView(target, java.nio.file.attribute.DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            targetDos.setHidden(sourceDos.isHidden()); targetDos.setSystem(sourceDos.isSystem()); targetDos.setArchive(sourceDos.isArchive());
        }
        checkpoint(Event.TARGET_VERIFY, item.path());
        verifyHash(target, item.hash());
        return new Item(item.id(), item.path(), item.hash(), Stamp.of(attributes(target)));
    }

    private void copy(InputStream input, Path target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (var output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            for (int count; (count = input.read(buffer)) != -1;) {
                var bytes = java.nio.ByteBuffer.wrap(buffer, 0, count);
                writeCopy(output, bytes);
            }
            output.force(true);
        }
    }

    protected void writeCopy(FileChannel output, java.nio.ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) output.write(bytes);
    }

    @Override protected void write(Path temporary, byte[] content) throws IOException {
        super.write(temporary, content);
        try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
    }
}
