package com.vrn.core;

import java.nio.file.Path;

/**
 * Represents a file that has been scanned by {@link FileScanner}.
 *
 * <p>Compatibility result for callers explicitly requesting captured bytes.
 * The CLI add path uses metadata-only SelectedFile entries and streaming
 * ObjectStore capture instead; it never accumulates these byte arrays.
 */
public record ScannedFile(Path path, String hash, byte[] bytes) {
}
