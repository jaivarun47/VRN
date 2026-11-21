package com.vrn.core;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Observable internal-entry checks, not a race-free descriptor-relative filesystem boundary. */
final class MetadataPaths {
    private final Path storage;
    private final Path realStorage;

    MetadataPaths(Path storage) throws IOException {
        this.storage = storage.toAbsolutePath().normalize();
        requireType(this.storage, true, true);
        // The caller-selected project's ancestors are allowed; internal entries are not aliases.
        realStorage = this.storage.toRealPath();
    }

    Path directory(String relative) throws IOException {
        Path parts = relative(relative);
        Path current = storage;
        Path realParent = realStorage;
        requireType(storage, true, true);
        if (!storage.toRealPath().toString().equals(realStorage.toString())) throw unsafe(storage);
        for (Path part : parts) {
            current = current.resolve(part);
            requireType(current, true, true);
            Path real = current.toRealPath();
            if (!real.getParent().toString().equals(realParent.toString())) throw unsafe(current);
            realParent = real;
        }
        return current;
    }

    Path file(String relative, boolean required) throws IOException {
        Path parts = relative(relative);
        Path parent = parts.getParent() == null ? checkedRoot() : directory(parts.getParent().toString());
        Path file = parent.resolve(parts.getFileName());
        requireType(file, false, required);
        return file;
    }

    List<String> readLines(String relative) throws IOException {
        Path path = file(relative, false);
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),
                     StandardCharsets.UTF_8.newDecoder()))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        }
    }

    byte[] readBytes(String relative) throws IOException {
        Path path = file(relative, false);
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) {
            return input.readAllBytes();
        }
    }

    String readString(String relative) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readBytes(relative))).toString();
    }

    private Path checkedRoot() throws IOException {
        requireType(storage, true, true);
        if (!storage.toRealPath().toString().equals(realStorage.toString())) throw unsafe(storage);
        return storage;
    }

    private static Path relative(String name) throws IOException {
        Path path = Path.of(name);
        if (path.isAbsolute() || name.isEmpty()) throw unsafe(path);
        for (Path part : path) {
            if (part.toString().equals(".") || part.toString().equals("..") || part.toString().isEmpty()) throw unsafe(path);
        }
        return path;
    }

    private static void requireType(Path path, boolean directory, boolean required) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            if (!required) return;
            throw new IOException("Incomplete repository: required metadata is missing: " + path, absent);
        }
        // Windows junctions can satisfy isDirectory(NOFOLLOW_LINKS); inspect isOther as well.
        if (attributes.isSymbolicLink() || attributes.isOther()) throw unsafe(path);
        if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
            throw new IOException("Invalid repository metadata: wrong type for required "
                    + (directory ? "directory: " : "regular file: ") + path);
        }
    }

    private static IOException unsafe(Path path) {
        return new IOException("Unsafe metadata boundary: unsupported symbolic link, junction, reparse point or resolved location: " + path);
    }
}
