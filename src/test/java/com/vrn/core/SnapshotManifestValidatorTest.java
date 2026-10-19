package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotManifestValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"ordinary.txt", "src/main/App.java", "config=production.txt", "#notes.txt",
            "資料/नमस्ते/é😀.txt", "a folder/file with spaces.txt", ".vrnignore", ".vrn-backup/file.txt",
            "src/.vrn/example.txt", "src/.VRNAUTO/example.txt", "COM10.txt", "auxiliary.txt"})
    void acceptsSupportedRelativePathsWithoutChangingSpelling(String path) {
        SnapshotManifestValidator.validatePath(path);
        SnapshotManifestValidator.validatePaths(List.of(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/absolute", "C:/absolute", "C:drive-relative", "//server/share/file",
            "\\rooted", "\\\\server\\share\\file", "\\\\?\\C:\\file", "a\\b.txt", ".", "..", "../escape",
            "a/../b", "a/./b", "./a", "a//b", "a/", "a/..", "file:stream", "wild*.txt", "wild?.txt",
            "bad<name", "bad>name", "bad\"name", "bad|name", "a\nb", "a\tb", "a\0b", "a\u007fb",
            " name", "name ", "name.", "dir./file", "NUL", "con.txt", "folder/PRN.log", "AUX",
            "COM1.txt", "lpt9", "COM¹.txt", "LPT².log", "COM³", "CON .txt", "CONIN$", "CONOUT$.txt",
            ".vrn", ".VRN/HEAD", ".vrnauto/objects/hash", ".VRNAUTO", "bad\uD800", "bad\uDC00"})
    void rejectsUnsafeNoncanonicalAndUnsupportedPaths(String path) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SnapshotManifestValidator.validatePaths(List.of(path)));
        assertTrue(error.getMessage().startsWith("Invalid VRN manifest:"));
    }

    @ParameterizedTest
    @CsvSource({"same.txt,same.txt", "a.txt,A.TXT", "node,node/child.txt", "Node,node/child.txt",
            "node,Node/child.txt", "src/Main.java,SRC/main.java", "Src/a.txt,src/b.txt"})
    void rejectsDuplicateCaseAliasAndPrefixConflicts_inBothOrders(String first, String second) {
        for (List<String> order : List.of(List.of(first, second), List.of(second, first))) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> SnapshotManifestValidator.validatePaths(order));
            assertTrue(error.getMessage().contains(first), error.getMessage());
            assertTrue(error.getMessage().contains(second), error.getMessage());
        }
    }

    @Test
    void rejectsEquivalentButNoncanonicalRepresentationsRatherThanRepairingThem() {
        for (String alias : List.of("src//a.txt", "src/./a.txt", "src/sub/../a.txt", "src\\a.txt")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SnapshotManifestValidator.validatePaths(List.of("src/a.txt", alias)));
        }
    }

    @Test
    void allowsSharedDirectoriesSiblingFilesAndDistinctUnicodeSpellings() {
        SnapshotManifestValidator.validatePaths(List.of("src/a", "src/b", "src/sub/c", "src-old",
                "src2/c", "src/a.txt", "é.txt", "e\u0301.txt"));
        SnapshotManifestValidator.validatePaths(List.of());
    }

    @Test
    void rejectsAFileAboveSeveralPreviouslyInsertedDescendants() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SnapshotManifestValidator.validatePaths(List.of("a/b/c", "a/d/e", "a")));
        assertTrue(error.getMessage().contains("file/directory conflict"));
        assertTrue(error.getMessage().contains("replacement are not supported"));
    }

    @Test
    void componentLengthIsMeasuredInUtf16CodeUnits() {
        SnapshotManifestValidator.validatePath("x".repeat(255));
        SnapshotManifestValidator.validatePath("😀".repeat(127) + "x");
        assertThrows(IllegalArgumentException.class, () -> SnapshotManifestValidator.validatePath("x".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> SnapshotManifestValidator.validatePath("😀".repeat(128)));
    }

    @Test
    void rejectsNullPathsAndMissingCollectionsClearly() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotManifestValidator.validatePath(null));
        assertThrows(IllegalArgumentException.class, () -> SnapshotManifestValidator.validatePaths(null));
        assertThrows(IllegalArgumentException.class, () -> SnapshotManifestValidator.validatePaths(Arrays.asList("a", null)));
    }
}
