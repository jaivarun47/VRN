package com.vrn.core;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * An immutable description of a single VRN snapshot (commit).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id}       – unique identifier for this snapshot (UUID, no dashes)</li>
 *   <li>{@code parentId} – id of the previous snapshot, or {@code "NONE"} for the first commit</li>
 *   <li>{@code timestamp}– epoch-milliseconds when the commit was created</li>
 *   <li>{@code message}  – the commit message supplied by the user</li>
 *   <li>{@code files}    – project-relative path → SHA-256 hash, copied from the staging index</li>
 * </ul>
 */
public record Snapshot(
        String id,
        String parentId,
        long timestamp,
        String message,
        Map<String, String> files
) {
    public Snapshot {
        // Freeze the manifest used for validation and serialization; retain its display spelling and order.
        if (files != null) {
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }
    }
}
