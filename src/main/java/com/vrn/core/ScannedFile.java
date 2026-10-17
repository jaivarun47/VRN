package com.vrn.core;

import java.nio.file.Path;

/**
 * Represents a file that has been scanned by {@link FileScanner}.
 *
 * <p>Carries both the project-relative path, the SHA-256 hash, and the
 * raw file bytes so that {@code AddCommand} can pass the bytes directly
 * to {@link ObjectStore} without reading the file a second time
 * (fixes the TOCTOU double-read bug).
 */
public record ScannedFile(Path path, String hash, byte[] bytes) {
}