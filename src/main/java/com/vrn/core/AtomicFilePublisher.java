package com.vrn.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Publishes a complete file through a unique temporary file in its directory.
 * Atomic replacement is required: there is deliberately no ordinary-move fallback.
 * Writes are closed before publication; power-loss durability is not promised.
 */
public class AtomicFilePublisher {

    public final void replace(Path destination, String content) throws IOException {
        replace(destination, content.getBytes(StandardCharsets.UTF_8));
    }

    public final void replace(Path destination, byte[] content) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + "-", ".tmp");
        try {
            write(temporary, content);
            move(temporary, target);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                delete(temporary);
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    // Narrow filesystem seams also permit deterministic publication-failure tests.
    protected void write(Path temporary, byte[] content) throws IOException {
        Files.write(temporary, content);
    }

    protected void move(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new AtomicMoveNotSupportedException(temporary.toString(), destination.toString(),
                    "Atomic replacement is required; the existing destination was not deliberately removed");
        }
    }

    public void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
