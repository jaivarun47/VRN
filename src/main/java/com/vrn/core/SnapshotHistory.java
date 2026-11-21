package com.vrn.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Observational ancestry anchored at one HEAD read; no guard, recovery or content-object access. */
public final class SnapshotHistory {
    private final Path storage;
    private final SnapshotStore snapshots;

    public SnapshotHistory(Path storage) { this(storage, new SnapshotStore(storage.toAbsolutePath().normalize())); }

    SnapshotHistory(Path storage, SnapshotStore snapshots) {
        this.storage=storage.toAbsolutePath().normalize();
        this.snapshots=snapshots;
    }

    public List<Entry> read() throws Exception {
        return walk(null).entries();
    }

    SnapshotResolver.Selection select(String selector) throws Exception {
        ReadResult result = walk(selector);
        if (result.target() == null) {
            if (selector.equals("HEAD") && result.head() == null) throw new IOException("No current snapshot: HEAD is NONE");
            throw new IOException("Snapshot '" + selector + "' is not in the current reachable history");
        }
        return new SnapshotResolver.Selection(result.head().id(), result.head(), result.target(),
                result.staging().operations(), result.staging().fingerprint());
    }

    private ReadResult walk(String selector) throws Exception {
        String current=RepositoryLayout.inspectedHead(storage);
        requireNoPendingRecovery(storage);
        StagingObservation staging = observeIndex(storage);
        var visited=new HashSet<String>();
        var entries=new ArrayList<Entry>();
        String child=null;
        Snapshot head = null;
        Snapshot target = null;
        while (!current.equals("NONE")) {
            MetadataCodec.validateSnapshotId(current);
            if (!visited.add(current)) throw new IOException("History cycle detected at snapshot '"+current+"'");
            Snapshot snapshot;
            try { snapshot=snapshots.loadAnchored(current); }
            catch (Exception failure) {
                throw new IOException("Cannot read snapshot '"+current+"' referenced by "
                        +(child==null ? "HEAD" : "parent of '"+child+"'")+": "+failure.getMessage(),failure);
            }
            entries.add(new Entry(snapshot.id(),snapshot.parentId(),snapshot.timestamp(),snapshot.message()));
            if (selector != null) {
                if (child == null) head = snapshot;
                if (selector.equals("HEAD") ? child == null : current.equals(selector)) target = snapshot;
            }
            child=current;
            current=snapshot.parentId();
        }
        requireNoPendingRecovery(storage);
        return new ReadResult(List.copyOf(entries), head, target, staging);
    }

    static StagingObservation observeIndex(Path storage) throws Exception {
        try {
            byte[] bytes = new MetadataPaths(storage).readBytes("staging/index");
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            Map<String, StagedOperation> operations = MetadataCodec.decodeOperations(text.lines().toList());
            StagingIndex.validate(operations);
            return new StagingObservation(Map.copyOf(operations), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchFileException absentIndex) {
            return new StagingObservation(Map.of(), "ABSENT");
        }
    }

    static void requireNoPendingRecovery(Path storage) throws IOException {
        try {
            Path pending=new MetadataPaths(storage).file("staging/commit.pending",false);
            Files.readAttributes(pending,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) { return; }
        throw new IOException("Pending commit recovery; intentionally run an existing mutation command such as 'add .' or 'commit' to resolve recovery before retrying log");
    }

    /** Deliberately excludes the snapshot's file map. */
    public record Entry(String id,String parentId,long timestamp,String message) {}

    record StagingObservation(Map<String, StagedOperation> operations, String fingerprint) {}
    private record ReadResult(List<Entry> entries, Snapshot head, Snapshot target, StagingObservation staging) {}
}
