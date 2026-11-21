package com.vrn.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;

/** One add invocation, one guard, at most one authoritative index replacement. */
public final class AddService {
    private final Path root;
    private final Path storage;
    private final FileScanner scanner;
    private final ObjectStore objects;
    private final AtomicFilePublisher files;

    public AddService(Path projectRoot) {
        this(projectRoot, new FileScanner(), new ObjectStore(projectRoot.resolve(".vrn")), new AtomicFilePublisher());
    }

    // Existing filesystem seams are shared with focused, deterministic transaction tests.
    AddService(Path projectRoot, FileScanner scanner, ObjectStore objects, AtomicFilePublisher files) {
        root = projectRoot.toAbsolutePath().normalize();
        storage = root.resolve(".vrn");
        this.scanner = scanner;
        this.objects = objects;
        this.files = files;
    }

    public Result add(Path selection) throws Exception {
        Result completed = null;
        try (RepositoryMutationGuard ignored = RepositoryMutationGuard.acquire(storage)) {
            RepositoryLayout.validate(storage);
            new CommitRecovery(storage, files).recover();
            completed = prepareAndPublish(selection);
        } catch (Exception failure) {
            if (completed == null) throw failure;
            // Lock release cannot roll back an already confirmed complete index.
            return new Result(completed.selectedFiles(), appendWarning(completed.warning(),
                    "Operation completed, but lock release needs attention: " + failure.getMessage()));
        }
        return completed;
    }

    private Result prepareAndPublish(Path selection) throws Exception {
        // Recovery may legitimately consume an older index. This is the transaction baseline.
        byte[] baseline = indexBytes();
        byte[] candidate = null;
        boolean publicationAttempted = false;
        boolean replacementReturned = false;
        int count = 0;
        try {
            StagingArea staging = new StagingArea(storage, files);
            var entries = staging.readOperationsLocked();
            String id = new MetadataPaths(storage).readString("HEAD").strip();
            Map<String, String> head = id.equals("NONE") ? Map.of() : new SnapshotStore(storage).load(id).files();
            var known = new LinkedHashSet<>(head.keySet());
            known.addAll(entries.keySet());
            var selected = scanner.selectForAddLocked(root, selection, known);
            count = selected.size();
            if (count == 0) return new Result(0, null);
            PathTransitions.prepare(root, selection, head, entries, selected);
            for (SelectedFile file : selected) {
                try {
                    String hash = objects.captureLocked(root, file);
                    entries.put(file.path().toString().replace('\\', '/'), new StagedOperation.Put(hash));
                } catch (Exception failure) {
                    throw new IOException("Preparing PUT '" + file.path().toString().replace('\\', '/')
                            + "': " + failure.getMessage(), failure);
                }
            }
            StagingIndex.apply(head, entries);
            var references = new LinkedHashSet<String>();
            for (var entry : entries.entrySet()) {
                switch (entry.getValue()) {
                    case StagedOperation.Put put -> references.add(put.contentHash());
                    case StagedOperation.Delete ignored -> {
                        if (!head.containsKey(entry.getKey())) {
                            throw new IOException("DELETE path is not tracked in HEAD: " + entry.getKey());
                        }
                    }
                }
            }
            objects.verifyAll(references);
            candidate = MetadataCodec.encodeOperations(entries).getBytes(StandardCharsets.UTF_8);
            Path index = new MetadataPaths(storage).file("staging/index", false);
            publicationAttempted = true;
            files.replace(index, candidate); // The sole logical commit point.
            replacementReturned = true;
            if (!Arrays.equals(candidate, indexBytes())) throw new IOException("Index replacement could not be confirmed");
            return new Result(count, null);
        } catch (Exception failure) {
            byte[] observed;
            try { observed = indexBytes(); }
            catch (Exception inspectionFailure) {
                failure.addSuppressed(inspectionFailure);
                throw new IOException("Staging outcome is uncertain; the authoritative index could not be inspected. Inspect repository state before retrying.", failure);
            }
            // An identical baseline is not evidence that a failed replacement actually ran.
            if (publicationAttempted && Arrays.equals(candidate, observed)
                    && (replacementReturned || !Arrays.equals(baseline, candidate))) {
                return new Result(count, "The complete staging index is published, but publication follow-up needs attention: " + failure.getMessage());
            }
            if (Arrays.equals(baseline, observed)) {
                throw new IOException(failure.getMessage()
                        + "; no partial staging-index update was published. The post-recovery baseline index is unchanged.", failure);
            }
            throw new IOException("Staging outcome is uncertain; observed index differs from the baseline and complete candidate. Inspect repository state before retrying.", failure);
        }
    }

    private byte[] indexBytes() throws IOException {
        try { return new MetadataPaths(storage).readBytes("staging/index"); }
        catch (NoSuchFileException absent) { return null; }
    }

    private static String appendWarning(String existing, String next) { return existing == null ? next : existing + "; " + next; }

    public record Result(int selectedFiles, String warning) {}
}
