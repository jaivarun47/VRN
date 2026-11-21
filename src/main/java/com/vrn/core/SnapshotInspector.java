package com.vrn.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.TreeMap;

/** Read-only inspection; every distinct target object is streamed and verified before success. */
public final class SnapshotInspector {
    private final SnapshotResolver resolver;
    private final ObjectStore objects;
    private final Path storage;

    public SnapshotInspector(Path storage) { this(storage, new ObjectStore(storage)); }

    SnapshotInspector(Path storage, ObjectStore objects) {
        this.storage = storage.toAbsolutePath().normalize();
        this.resolver = new SnapshotResolver(storage);
        this.objects = objects;
    }

    public Snapshot inspect(String selector) throws Exception {
        return verifiedSelection(selector).target();
    }

    SnapshotResolver.Selection verifiedSelection(String selector) throws Exception {
        var selected = resolver.resolve(selector);
        Snapshot source = selected.target();
        var sorted = new TreeMap<>(source.files());
        var verified = new HashSet<String>();
        for (var entry : sorted.entrySet()) {
            if (!verified.add(entry.getValue())) continue;
            try { objects.verify(entry.getValue()); }
            catch (IOException failure) {
                throw new IOException("Snapshot '" + source.id() + "' file '" + entry.getKey()
                        + "' object '" + entry.getValue() + "' cannot be verified: " + failure.getMessage(), failure);
            }
        }
        // Reject a pending record that appeared during a long object read too.
        SnapshotHistory.requireNoPendingRecovery(storage);
        Snapshot target = new Snapshot(source.id(), source.parentId(), source.timestamp(), source.message(), sorted);
        return new SnapshotResolver.Selection(selected.headId(), selected.head(), target, selected.staging(), selected.stagingFingerprint());
    }

}
