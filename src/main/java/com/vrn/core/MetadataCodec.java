package com.vrn.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless serialization shared by stores at any supplied storage location.
 *
 * <p>Version 1 uses a type/version header, fixed tab-separated fields, an exact
 * file count, and {@code file<TAB>base64(path)<TAB>object-reference} entries.
 * Paths and messages use canonical Base64 of strict UTF-8, so their delimiters
 * and line endings are data. Identifiers are safe opaque tokens, not necessarily
 * UUIDs; object-reference syntax is checked here, not SHA-256/object integrity.
 *
 * <p>Legacy reads accept the original ordered snapshot fields and single-equals
 * file entries. Ambiguous comments, extra lines/delimiters and duplicate entries
 * are rejected. Reading never migrates files. Already-lost data cannot be inferred.
 */
final class MetadataCodec {

    private static final String INDEX_HEADER = "VRN-STAGING\t1";
    private static final String SNAPSHOT_HEADER = "VRN-SNAPSHOT\t1";

    private MetadataCodec() {
    }

    static String encodeIndex(Map<String, String> files) {
        StringBuilder result = new StringBuilder(INDEX_HEADER).append('\n');
        appendFiles(result, files);
        return result.toString();
    }

    static Map<String, String> decodeIndex(List<String> lines) {
        if (hasHeader(lines)) {
            requireHeader(lines, INDEX_HEADER);
            return readFiles(lines, 1);
        }
        return readLegacyFiles(lines, 0, false);
    }

    static String encodeSnapshot(Snapshot snapshot) {
        if (snapshot == null) {
            throw invalid("snapshot is required");
        }
        validateSnapshotId(snapshot.id());
        validateParent(snapshot.parentId());
        if (snapshot.timestamp() < 0) {
            throw invalid("timestamp must be nonnegative");
        }
        StringBuilder result = new StringBuilder(SNAPSHOT_HEADER).append('\n');
        result.append("id\t").append(snapshot.id()).append('\n');
        result.append("parent\t").append(snapshot.parentId()).append('\n');
        result.append("timestamp\t").append(snapshot.timestamp()).append('\n');
        result.append("message\t").append(encodeText(snapshot.message(), "message")).append('\n');
        appendFiles(result, snapshot.files());
        return result.toString();
    }

    static Snapshot decodeSnapshot(String expectedId, List<String> lines) {
        validateSnapshotId(expectedId);
        String id;
        String parent;
        long timestamp;
        String message;
        Map<String, String> files;
        if (hasHeader(lines)) {
            requireHeader(lines, SNAPSHOT_HEADER);
            id = readField(lines, 1, "id");
            parent = readField(lines, 2, "parent");
            timestamp = readTimestamp(readField(lines, 3, "timestamp"));
            message = decodeText(readField(lines, 4, "message"), "message");
            files = readFiles(lines, 5);
        } else {
            id = legacyField(lines, 0, "id");
            parent = legacyField(lines, 1, "parent");
            timestamp = readTimestamp(legacyField(lines, 2, "timestamp"));
            message = legacyField(lines, 3, "message");
            if (lines.size() < 5 || !lines.get(4).equals("[files]")) {
                throw invalid("legacy snapshot requires [files] immediately after a single-line message; "
                        + "ambiguous multiline metadata cannot be recovered automatically");
            }
            files = readLegacyFiles(lines, 5, true);
        }
        validateSnapshotId(id);
        validateParent(parent);
        if (!expectedId.equals(id)) {
            throw invalid("snapshot id does not match filename: " + expectedId);
        }
        return new Snapshot(id, parent, timestamp, message, files);
    }

    static void validateSnapshotId(String id) {
        validateToken(id, "snapshot id");
        if (id.equals("NONE")) {
            throw invalid("NONE is reserved for the root parent, not a snapshot id");
        }
    }

    private static void validateParent(String parent) {
        validateToken(parent, "parent id");
    }

    private static void validateToken(String token, String field) {
        if (token == null || !token.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw invalid(field + " must be a nonempty alphanumeric token with optional '-' or '_'");
        }
    }

    private static void validatePath(String path) {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0) {
            throw invalid("file path must be nonempty and contain no NUL characters");
        }
        // Filesystem identity, containment and manifest conflicts are separate checks.
    }

    private static boolean hasHeader(List<String> lines) {
        return !lines.isEmpty() && lines.getFirst().startsWith("VRN-")
                && (lines.getFirst().contains("\t") || !lines.getFirst().contains("="));
    }

    private static void requireHeader(List<String> lines, String expected) {
        if (!lines.getFirst().equals(expected)) {
            throw invalid("unsupported metadata type/version: " + lines.getFirst()
                    + "; expected " + expected);
        }
    }

    private static String readField(List<String> lines, int index, String name) {
        if (index >= lines.size()) {
            throw invalid("missing " + name + " field");
        }
        String[] parts = lines.get(index).split("\t", -1);
        if (parts.length != 2 || !parts[0].equals(name)) {
            throw invalid("expected a single " + name + " field at line " + (index + 1));
        }
        return parts[1];
    }

    private static String legacyField(List<String> lines, int index, String name) {
        String prefix = name + "=";
        if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
            throw invalid("legacy snapshot requires " + name + " at line " + (index + 1));
        }
        return lines.get(index).substring(prefix.length());
    }

    private static long readTimestamp(String value) {
        try {
            if (!value.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid("timestamp must be a nonnegative 64-bit integer");
        }
    }

    private static void appendFiles(StringBuilder result, Map<String, String> files) {
        if (files == null) {
            throw invalid("file manifest is required");
        }
        result.append("files\t").append(files.size()).append('\n');
        for (Map.Entry<String, String> entry : files.entrySet()) {
            validatePath(entry.getKey());
            validateToken(entry.getValue(), "object reference");
            result.append("file\t").append(encodeText(entry.getKey(), "file path"))
                    .append('\t').append(entry.getValue()).append('\n');
        }
    }

    private static Map<String, String> readFiles(List<String> lines, int countLine) {
        String value = readField(lines, countLine, "files");
        int count;
        try {
            if (!value.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            count = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalid("file count must be a nonnegative 32-bit integer");
        }
        if (count != lines.size() - countLine - 1) {
            throw invalid("file count does not match the number of entries");
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = countLine + 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\t", -1);
            if (parts.length != 3 || !parts[0].equals("file")) {
                throw invalid("malformed file entry at line " + (i + 1));
            }
            addEntry(files, decodeText(parts[1], "file path at line " + (i + 1)), parts[2]);
        }
        return files;
    }

    private static Map<String, String> readLegacyFiles(List<String> lines, int start, boolean snapshot) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            int separator = line.indexOf('=');
            if (snapshot && line.startsWith("#")) {
                throw invalid("ambiguous legacy snapshot comment/file entry at line " + (i + 1));
            }
            if (separator <= 0 || separator != line.lastIndexOf('=')) {
                throw invalid("legacy file entry at line " + (i + 1)
                        + " must have exactly one '='; ambiguous entries cannot be recovered automatically");
            }
            addEntry(files, line.substring(0, separator), line.substring(separator + 1));
        }
        return files;
    }

    private static void addEntry(Map<String, String> files, String path, String reference) {
        validatePath(path);
        validateToken(reference, "object reference");
        if (files.putIfAbsent(path, reference) != null) {
            throw invalid("duplicate file path: " + path);
        }
    }

    private static String encodeText(String value, String field) {
        if (value == null) {
            throw invalid(field + " is required");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (CharacterCodingException e) {
            throw invalid(field + " must contain valid Unicode");
        }
    }

    private static String decodeText(String value, String field) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw invalid(field + " must contain valid Base64");
        }
        if (!Base64.getEncoder().encodeToString(bytes).equals(value)) {
            throw invalid(field + " must use canonical Base64 encoding");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw invalid(field + " must contain valid UTF-8");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid VRN metadata: " + message);
    }
}
