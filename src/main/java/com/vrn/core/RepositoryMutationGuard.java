package com.vrn.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Fail-fast, non-reentrant writer coordination for one supplied storage directory.
 * The persistent lock file must not be deleted: the OS lock, not its existence,
 * determines ownership. Separate storage directories have independent locks.
 */
public final class RepositoryMutationGuard implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private RepositoryMutationGuard(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static RepositoryMutationGuard acquire(Path storage) throws IOException {
        RepositoryLayout.preflight(storage);
        Path lockPath = new MetadataPaths(storage).file("mutation.lock", false);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("Repository is busy; retry the operation: " + storage, failure);
            }
            if (lock == null) {
                throw new IOException("Repository is busy; retry the operation: " + storage);
            }
            // Recheck under the guard; the first check makes acquiring the guard itself safe.
            RepositoryLayout.preflight(storage);
            return new RepositoryMutationGuard(channel, lock);
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
