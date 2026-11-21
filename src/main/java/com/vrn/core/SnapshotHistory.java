package com.vrn.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
        String current=RepositoryLayout.inspectedHead(storage);
        requireNoPendingRecovery();
        try {
            StagingIndex.validate(MetadataCodec.decodeOperations(new MetadataPaths(storage).readLines("staging/index")));
        } catch (NoSuchFileException absentIndex) { /* Optional; never create an empty replacement. */ }
        var visited=new HashSet<String>();
        var entries=new ArrayList<Entry>();
        String child=null;
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
            child=current;
            current=snapshot.parentId();
        }
        requireNoPendingRecovery();
        return List.copyOf(entries);
    }

    private void requireNoPendingRecovery() throws IOException {
        try {
            Path pending=new MetadataPaths(storage).file("staging/commit.pending",false);
            Files.readAttributes(pending,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) { return; }
        throw new IOException("Pending commit recovery; intentionally run an existing mutation command such as 'add .' or 'commit' to resolve recovery before retrying log");
    }

    /** Deliberately excludes the snapshot's file map. */
    public record Entry(String id,String parentId,long timestamp,String message) {}
}
