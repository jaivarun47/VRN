package com.vrn.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manual staged-commit operation against explicitly supplied repository storage.
 * Cooperating writers are serialized; source-file edits and whole multi-file add
 * operations are not frozen. Unreachable manifests are retained for later inspection.
 */
public final class CommitService {

    private final Path storage;
    private final AtomicFilePublisher files;

    public CommitService(Path storage) {
        this(storage, new AtomicFilePublisher());
    }

    CommitService(Path storage, AtomicFilePublisher files) {
        this.storage = storage;
        this.files = files;
    }

    /**
     * HEAD replacement is the commit point. Confirmed publication followed by an
     * exception produces a warning. An unreadable HEAD after a rename error leaves
     * an explicitly uncertain outcome and recovery data, rather than a guessed result.
     */
    public Result commit(String message) throws Exception {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Commit message cannot be empty.");
        }
        String publishedId = null;
        String warning = null;
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            CommitRecovery recovery = new CommitRecovery(storage, files);
            recovery.recover();
            StagingArea staging = new StagingArea(storage, files);
            Map<String, String> staged = staging.readAllLocked();
            if (staged.isEmpty()) {
                throw new IllegalStateException("Nothing to commit. Stage files first with: vrn add <path>");
            }

            Path head = storage.resolve("HEAD");
            String parentId = Files.readString(head).strip();
            SnapshotStore snapshots = new SnapshotStore(storage, files);
            Map<String, String> manifest = new LinkedHashMap<>();
            if (!parentId.equals("NONE")) {
                manifest.putAll(snapshots.load(parentId).files());
            }
            manifest.putAll(staged);
            String id = UUID.randomUUID().toString().replace("-", "");
            snapshots.saveLocked(new Snapshot(id, parentId, System.currentTimeMillis(), message, manifest));
            recovery.prepare(id, parentId);
            try {
                files.replace(head, id);
            } catch (Exception publicationFailure) {
                // Reconcile while still holding the guard: another commit must
                // not advance HEAD between a rename error and this inspection.
                try {
                    if (Files.readString(head).strip().equals(id)) {
                        publishedId = id;
                    }
                } catch (Exception inspectionFailure) {
                    publicationFailure.addSuppressed(inspectionFailure);
                    throw new IOException("Commit outcome is uncertain for snapshot " + id
                            + "; HEAD could not be inspected. Recover before retrying.", publicationFailure);
                }
                throw publicationFailure;
            }
            publishedId = id;
            recovery.recover();
        } catch (Exception failure) {
            if (publishedId == null) {
                throw failure;
            }
            warning = "Snapshot is active, but publication follow-up, cleanup or lock release needs attention: "
                    + failure.getMessage();
        }
        return new Result(publishedId, warning);
    }

    public record Result(String snapshotId, String warning) {
    }
}
