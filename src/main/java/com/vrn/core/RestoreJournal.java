package com.vrn.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Versioned, checksummed write-ahead record. Resource names are derived from numeric IDs only. */
record RestoreJournal(String operationId, String root, Stamp rootStamp, String head, String target,
                      String headBytesHash, String indexHash, String headManifestHash, String targetManifestHash,
                      Phase phase, int cursor, boolean inFlight, List<Item> originals, List<Item> targets,
                      List<Directory> directories) {
    enum Phase { PREPARING, APPLYING, ROLLING_BACK, COMPLETE, ROLLED_BACK, ABORTED }
    enum Kind { DETACH_FILE, DETACH_DIRECTORY, ATTACH_DIRECTORY, PUBLISH_FILE }
    record Item(int id, String path, String hash, Stamp stamp) {}
    record Directory(int id, String original, String target, Stamp stamp) {}
    record Step(Kind kind, int id, String path) {}
    record Stamp(String created, String modified, long size, String key) {
        static Stamp of(BasicFileAttributes attrs) {
            return new Stamp(attrs.creationTime().toString(), attrs.lastModifiedTime().toString(), attrs.size(),
                    attrs.fileKey() == null ? null : attrs.fileKey().toString());
        }
        boolean matches(BasicFileAttributes attrs, boolean directory) {
            return created.equals(attrs.creationTime().toString())
                    && (key == null || (attrs.fileKey() != null && key.equals(attrs.fileKey().toString())))
                    && (directory || (size == attrs.size() && modified.equals(attrs.lastModifiedTime().toString())));
        }
    }

    RestoreJournal {
        originals = List.copyOf(originals); targets = List.copyOf(targets); directories = List.copyOf(directories);
    }

    RestoreJournal progress(Phase next, int position, boolean uncertain) {
        return new RestoreJournal(operationId, root, rootStamp, head, target, headBytesHash, indexHash,
                headManifestHash, targetManifestHash, next, position, uncertain, originals, targets, directories);
    }

    RestoreJournal prepared(List<Item> files, List<Directory> dirs) {
        return new RestoreJournal(operationId, root, rootStamp, head, target, headBytesHash, indexHash,
                headManifestHash, targetManifestHash, Phase.APPLYING, 0, false, originals, files, dirs);
    }

    List<Step> steps() {
        var steps = new ArrayList<Step>();
        originals.stream().sorted(Comparator.comparing(Item::path)).forEach(item -> steps.add(new Step(Kind.DETACH_FILE, item.id(), item.path())));
        directories.stream().filter(dir -> dir.original() != null)
                .sorted(Comparator.<Directory>comparingInt(dir -> depth(dir.original())).reversed().thenComparing(Directory::original))
                .forEach(dir -> steps.add(new Step(Kind.DETACH_DIRECTORY, dir.id(), dir.original())));
        directories.stream().filter(dir -> dir.target() != null)
                .sorted(Comparator.<Directory>comparingInt(dir -> depth(dir.target())).thenComparing(Directory::target))
                .forEach(dir -> steps.add(new Step(Kind.ATTACH_DIRECTORY, dir.id(), dir.target())));
        targets.stream().sorted(Comparator.comparing(Item::path)).forEach(item -> steps.add(new Step(Kind.PUBLISH_FILE, item.id(), item.path())));
        return List.copyOf(steps);
    }

    boolean finished() { return phase == Phase.COMPLETE || phase == Phase.ROLLED_BACK || phase == Phase.ABORTED; }
    static int depth(String path) { return path.split("/").length; }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    String encode() throws IOException {
        validate();
        var rows = new ArrayList<String>();
        rows.add("VRN-RESTORE\t1");
        rows.add("operation\t" + operationId); rows.add("root\t" + text(root)); rows.add("root-stamp\t" + stamp(rootStamp));
        rows.add("head\t" + head); rows.add("target\t" + target); rows.add("head-bytes\t" + headBytesHash);
        rows.add("index\t" + indexHash); rows.add("head-manifest\t" + headManifestHash); rows.add("target-manifest\t" + targetManifestHash);
        rows.add("phase\t" + phase); rows.add("cursor\t" + cursor); rows.add("in-flight\t" + inFlight);
        rows.add("originals\t" + originals.size());
        for (Item item : originals) rows.add(item("original", item));
        rows.add("targets\t" + targets.size());
        for (Item item : targets) rows.add(item("target-file", item));
        rows.add("directories\t" + directories.size());
        for (Directory dir : directories) rows.add("directory\t" + dir.id() + "\t" + nullable(dir.original()) + "\t" + nullable(dir.target()) + "\t" + stamp(dir.stamp()));
        String body = String.join("\n", rows) + "\n";
        return body + "checksum\t" + digest(body.getBytes(StandardCharsets.UTF_8)) + "\n";
    }

    static RestoreJournal decode(List<String> rows) throws IOException {
        try {
            if (rows.size() < 17 || !rows.getFirst().equals("VRN-RESTORE\t1")) throw new IOException("Invalid or unsupported restoration journal header");
            String checksum = field(rows.getLast(), "checksum");
            String body = String.join("\n", rows.subList(0, rows.size() - 1)) + "\n";
            if (!digest(body.getBytes(StandardCharsets.UTF_8)).equals(checksum)) throw new IOException("Restoration journal checksum mismatch");
            var reader = new Reader(rows);
            String operation = reader.field("operation"), root = untext(reader.field("root"));
            Stamp rootStamp = unstamp(reader.field("root-stamp"));
            String head = reader.field("head"), target = reader.field("target"), headHash = reader.field("head-bytes"), index = reader.field("index");
            String headManifest = reader.field("head-manifest"), targetManifest = reader.field("target-manifest");
            Phase phase = Phase.valueOf(reader.field("phase"));
            int cursor = Integer.parseInt(reader.field("cursor"));
            String flight = reader.field("in-flight");
            if (!flight.equals("true") && !flight.equals("false")) throw new IOException("Invalid in-flight flag");
            var originals = reader.items("originals", "original");
            var targets = reader.items("targets", "target-file");
            int count = reader.count("directories");
            var directories = new ArrayList<Directory>();
            for (int i = 0; i < count; i++) {
                String[] parts = reader.parts("directory", 5);
                directories.add(new Directory(Integer.parseInt(parts[1]), unnullable(parts[2]), unnullable(parts[3]), unstamp(parts[4])));
            }
            if (reader.position != rows.size() - 1) throw new IOException("Unexpected restoration journal fields");
            var journal = new RestoreJournal(operation, root, rootStamp, head, target, headHash, index, headManifest, targetManifest,
                    phase, cursor, Boolean.parseBoolean(flight), originals, targets, directories);
            journal.validate(); return journal;
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Malformed restoration journal: " + failure.getMessage(), failure); }
    }

    void validate() throws IOException {
        try {
            if (operationId == null || !operationId.matches("[0-9a-f]{32}")) throw new IOException("Invalid restore operation ID");
            if (root == null || !java.nio.file.Path.of(root).isAbsolute() || rootStamp == null) throw new IOException("Invalid restore root identity");
            MetadataCodec.validateSnapshotId(head); MetadataCodec.validateSnapshotId(target);
            for (String hash : List.of(headBytesHash, headManifestHash, targetManifestHash)) ObjectStore.validateIdentifier(hash);
            if (!indexHash.equals("ABSENT")) ObjectStore.validateIdentifier(indexHash);
            validateStamp(rootStamp);
            boolean prepared = phase != Phase.PREPARING && phase != Phase.ABORTED;
            validateItems(originals, true); validateItems(targets, prepared);
            var originalDirs = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            var targetDirs = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 0; i < directories.size(); i++) {
                Directory dir = directories.get(i);
                if (dir.id() != i || (dir.original() == null && dir.target() == null)) throw new IOException("Invalid directory catalog");
                if (dir.original() != null) { SnapshotManifestValidator.validatePath(dir.original()); if (!originalDirs.add(dir.original())) throw new IOException("Duplicate original directory"); }
                if (dir.target() != null) { SnapshotManifestValidator.validatePath(dir.target()); if (!targetDirs.add(dir.target())) throw new IOException("Duplicate target directory"); }
                if (dir.original() != null && dir.target() != null && !dir.original().equalsIgnoreCase(dir.target())) throw new IOException("Directory reuse must preserve identity");
                if (prepared && dir.stamp() == null) throw new IOException("Unprepared directory");
                if (dir.stamp() != null) validateStamp(dir.stamp());
            }
            int size = steps().size();
            if (cursor < 0 || cursor > size || size == 0) throw new IOException("Invalid restore progress");
            if ((phase == Phase.PREPARING || phase == Phase.ABORTED || phase == Phase.ROLLED_BACK) && (cursor != 0 || inFlight)) throw new IOException("Invalid terminal/preparation cursor");
            if (phase == Phase.COMPLETE && (cursor != size || inFlight)) throw new IOException("Invalid completion cursor");
            if (inFlight && ((phase == Phase.APPLYING && cursor == size) || (phase == Phase.ROLLING_BACK && cursor == 0) || finished())) throw new IOException("Invalid uncertain step");
        } catch (IOException failure) { throw failure; }
        catch (RuntimeException failure) { throw new IOException("Invalid restoration journal: " + failure.getMessage(), failure); }
    }

    private static void validateItems(List<Item> items, boolean prepared) throws IOException {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i); if (item.id() != i) throw new IOException("Invalid file catalog ID");
            ObjectStore.validateIdentifier(item.hash());
            if (prepared && item.stamp() == null) throw new IOException("Missing prepared file identity");
            if (item.stamp() != null) validateStamp(item.stamp());
        }
        SnapshotManifestValidator.validatePaths(items.stream().map(Item::path).toList());
    }
    private static void validateStamp(Stamp stamp) throws IOException {
        if (stamp.size() < 0) throw new IOException("Invalid observed file size");
        Instant.parse(stamp.created()); Instant.parse(stamp.modified());
    }
    private static String item(String tag, Item item) { return tag + "\t" + item.id() + "\t" + text(item.path()) + "\t" + item.hash() + "\t" + stamp(item.stamp()); }
    private static String nullable(String value) { return value == null ? "-" : text(value); }
    private static String unnullable(String value) throws IOException { return value.equals("-") ? null : untext(value); }
    private static String text(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String untext(String value) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Base64.getDecoder().decode(value))).toString();
    }
    private static String stamp(Stamp stamp) {
        return stamp == null ? "-" : text(stamp.size() + "\n" + stamp.created() + "\n" + stamp.modified() + "\n" + nullable(stamp.key()));
    }
    private static Stamp unstamp(String value) throws IOException {
        if (value.equals("-")) return null;
        String[] parts = untext(value).split("\n", -1);
        if (parts.length != 4) throw new IOException("Invalid file stamp");
        return new Stamp(parts[1], parts[2], Long.parseLong(parts[0]), unnullable(parts[3]));
    }
    private static String field(String line, String tag) throws IOException {
        String[] parts = line.split("\t", -1);
        if (parts.length != 2 || !parts[0].equals(tag)) throw new IOException("Invalid restoration field: " + tag);
        return parts[1];
    }
    private static final class Reader {
        private final List<String> rows; int position = 1;
        Reader(List<String> rows) { this.rows = rows; }
        String field(String tag) throws IOException { return RestoreJournal.field(rows.get(position++), tag); }
        int count(String tag) throws IOException { int value = Integer.parseInt(field(tag)); if (value < 0 || value > rows.size()) throw new IOException("Invalid catalog count"); return value; }
        String[] parts(String tag, int count) throws IOException {
            String[] parts = rows.get(position++).split("\t", -1);
            if (parts.length != count || !parts[0].equals(tag)) throw new IOException("Invalid catalog row: " + tag); return parts;
        }
        List<Item> items(String countTag, String tag) throws IOException {
            int count = count(countTag); var items = new ArrayList<Item>();
            for (int i = 0; i < count; i++) { String[] p = parts(tag, 5); items.add(new Item(Integer.parseInt(p[1]), untext(p[2]), p[3], unstamp(p[4]))); }
            return items;
        }
    }
}
