package com.vrn.core;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;

/**
 * Initializes manual storage without replacing existing storage or user configuration.
 * Config is published last as the completion step; no whole-directory transaction,
 * rollback or power-loss durability is promised. Interrupted creation is preserved
 * for explicit recovery. A failure after the final rename may leave a complete
 * repository, which a subsequent invocation validates rather than resets.
 */
public final class RepositoryInitializer {

    private final AtomicFilePublisher files;

    public RepositoryInitializer() {
        this(new AtomicFilePublisher());
    }

    RepositoryInitializer(AtomicFilePublisher files) {
        this.files = files;
    }

    /** @return true for newly created storage, false for an already valid repository */
    public boolean initialize(Path projectRoot) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path storage = root.resolve(".vrn");
        if (Files.exists(storage, LinkOption.NOFOLLOW_LINKS)) {
            RepositoryLayout.validate(storage);
            return false;
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("Project directory does not exist: " + root);
        }
        Path ignore = root.resolve(".vrnignore");
        if (Files.exists(ignore, LinkOption.NOFOLLOW_LINKS)) {
            requireIgnoreFile(ignore);
        }

        // Exclusive creation claims only a new storage path; never reuse a partial repository.
        Files.createDirectory(storage);
        Files.createDirectory(storage.resolve("objects"));
        Files.createDirectory(storage.resolve("snapshots"));
        Files.createDirectory(storage.resolve("staging"));
        files.replace(storage.resolve("HEAD"), "NONE");

        try {
            // CREATE_NEW also protects a user file appearing after the preflight check.
            Files.writeString(ignore, ".vrn/\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException existing) {
            requireIgnoreFile(ignore);
        }

        DosFileAttributeView attributes = Files.getFileAttributeView(storage, DosFileAttributeView.class);
        if (attributes != null) {
            attributes.setHidden(true);
        }

        // All other initialization work precedes this atomic completion publication.
        files.replace(storage.resolve("config"), "version=1");
        return true;
    }

    private static void requireIgnoreFile(Path ignore) throws IOException {
        if (!Files.isRegularFile(ignore)) {
            throw new IOException("Existing .vrnignore is not a regular file; it was preserved: " + ignore);
        }
    }
}
