package com.vrn.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Exact membership in a completely validated, single-HEAD ancestry observation. */
public final class SnapshotResolver {
    private final Path storage;

    public SnapshotResolver(Path storage) {
        this.storage = storage.toAbsolutePath().normalize();
    }

    public Selection resolve(String selector) throws Exception {
        if (!"HEAD".equals(selector)) MetadataCodec.validateSnapshotId(selector);
        return new SnapshotHistory(storage).select(selector);
    }

    /** Optimistic end check for planning, not isolation or an execution-time safety check. */
    void requireUnchanged(Selection selection) throws Exception {
        SnapshotHistory.requireNoPendingRecovery(storage);
        if (!RepositoryLayout.inspectedHead(storage).equals(selection.headId())
                || !SnapshotHistory.observeIndex(storage).fingerprint().equals(selection.stagingFingerprint())) {
            throw new IOException("Repository HEAD or staging changed during planning; retry");
        }
        SnapshotStore snapshots = new SnapshotStore(storage);
        if (!snapshots.loadAnchored(selection.headId()).equals(selection.head())
                || !snapshots.loadAnchored(selection.target().id()).equals(selection.target())) {
            throw new IOException("Snapshot metadata changed during planning; retry");
        }
        SnapshotHistory.requireNoPendingRecovery(storage);
    }

    public record Selection(String headId, Snapshot head, Snapshot target,
                            Map<String, StagedOperation> staging, String stagingFingerprint) {
        public Selection { staging = Map.copyOf(staging); }
    }
}
