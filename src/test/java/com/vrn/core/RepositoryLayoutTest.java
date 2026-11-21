package com.vrn.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryLayoutTest {

    @TempDir
    Path root;
    private Path storage;

    @BeforeEach
    void initialize() throws IOException {
        new RepositoryInitializer().initialize(root);
        storage = root.resolve(".vrn");
    }

    @ParameterizedTest
    @ValueSource(strings = {"objects", "snapshots", "staging", "HEAD", "config"})
    void missingRequiredComponent_isRejected(String name) throws Exception {
        Files.delete(storage.resolve(name));

        IOException error = assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertTrue(error.getMessage().contains(name), error.getMessage());
        assertFalse(Files.exists(storage.resolve(name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"objects", "snapshots", "staging"})
    void requiredDirectoryReplacedByFile_isRejectedWithoutAlteringIt(String name) throws Exception {
        Path component = storage.resolve(name);
        Files.delete(component);
        Files.writeString(component, "user bytes");

        IOException error = assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertTrue(error.getMessage().contains(name));
        assertEquals("user bytes", Files.readString(component));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "config"})
    void requiredMetadataReplacedByDirectory_isRejected(String name) throws Exception {
        Files.delete(storage.resolve(name));
        Files.createDirectory(storage.resolve(name));

        IOException error = assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertTrue(error.getMessage().contains(name));
        assertTrue(Files.isDirectory(storage.resolve(name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "# no version\n", "version", "version=abc", "version=1\nversion=1\n",
            "version=1\nnot-a-setting\n", "=1"})
    void malformedConfiguration_isRejectedAndPreserved(String config) throws Exception {
        Files.writeString(storage.resolve("config"), config);

        IOException error = assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertTrue(error.getMessage().contains("Malformed config"), error.getMessage());
        assertEquals(config, Files.readString(storage.resolve("config")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2", "99999999999999999999999999999999999999"})
    void unsupportedRepositoryVersion_isRejectedWithoutMigration(String version) throws Exception {
        String config = "version=" + version;
        Files.writeString(storage.resolve("config"), config);

        IOException error = assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertTrue(error.getMessage().contains("Unsupported repository format version: " + version));
        assertEquals(config, Files.readString(storage.resolve("config")));
    }

    @Test
    void malformedUtf8Metadata_isRejectedWithoutReplacement() throws Exception {
        byte[] invalid = {(byte) 0xc3, (byte) 0x28};
        Files.write(storage.resolve("config"), invalid);

        assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertArrayEquals(invalid, Files.readAllBytes(storage.resolve("config")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n", "NONE\nnext", "../escape", "snap/child", "NONE\n\n"})
    void malformedHead_isRejectedWithoutResolvingUnsafeReferences(String head) throws Exception {
        Files.writeString(storage.resolve("HEAD"), head);

        assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertEquals(head, Files.readString(storage.resolve("HEAD")));
    }

    @Test
    void headReferencingMissingSnapshot_isRejected() throws Exception {
        Files.writeString(storage.resolve("HEAD"), "missing-snapshot");

        IOException error = assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertTrue(error.getMessage().contains("missing-snapshot.snapshot"));
        assertEquals("missing-snapshot", Files.readString(storage.resolve("HEAD")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed snapshot", "VRN-SNAPSHOT\t999\n"})
    void malformedOrUnsupportedActiveSnapshot_isRejected(String content) throws Exception {
        Files.writeString(storage.resolve("HEAD"), "active");
        Files.writeString(storage.resolve("snapshots/active.snapshot"), content);

        assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertEquals(content, Files.readString(storage.resolve("snapshots/active.snapshot")));
    }

    @Test
    void activeSnapshotWithIncorrectFileType_isRejected() throws Exception {
        Files.writeString(storage.resolve("HEAD"), "active");
        Files.createDirectory(storage.resolve("snapshots/active.snapshot"));

        assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertTrue(Files.isDirectory(storage.resolve("snapshots/active.snapshot")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"staging/index", "staging/commit.pending", "mutation.lock"})
    void optionalMetadataWithIncorrectFileType_isRejected(String name) throws Exception {
        Files.createDirectory(storage.resolve(name));

        IOException error = assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertTrue(error.getMessage().contains(name.replace('/', java.io.File.separatorChar)));
        assertTrue(Files.isDirectory(storage.resolve(name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a=b=c\n", "VRN-STAGING\t9\n", "VRN-STAGING\t1\nfiles\t2\n"})
    void invalidStagingMetadata_isRejectedWithoutClearingIt(String content) throws Exception {
        Files.writeString(storage.resolve("staging/index"), content);

        assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertEquals(content, Files.readString(storage.resolve("staging/index")));
    }

    @Test
    void malformedRecoveryMetadata_isRejectedWithoutDeletingIt() throws Exception {
        Path pending = storage.resolve("staging/commit.pending");
        Files.writeString(pending, "unknown recovery format");

        assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertEquals("unknown recovery format", Files.readString(pending));
    }

    @Test
    void unambiguousLegacyMetadata_isAcceptedWithoutMigration() throws Exception {
        String snapshot = "id=legacy-id\nparent=NONE\ntimestamp=1\nmessage=legacy message\n[files]\nfile.txt=legacy-hash\n";
        String index = "next.txt=legacy-hash\n";
        Files.writeString(storage.resolve("HEAD"), "legacy-id\n");
        Files.writeString(storage.resolve("snapshots/legacy-id.snapshot"), snapshot);
        Files.writeString(storage.resolve("staging/index"), index);

        RepositoryLayout.validate(storage);
        assertFalse(new RepositoryInitializer().initialize(root));

        assertEquals(snapshot, Files.readString(storage.resolve("snapshots/legacy-id.snapshot")));
        assertEquals(index, Files.readString(storage.resolve("staging/index")));
        assertFalse(Files.exists(storage.resolve("mutation.lock")), "Validation must not create a lock file");
    }

    @Test
    void layoutCheckDoesNotClaimFullAncestryOrObjectIntegrity() throws Exception {
        new SnapshotStore(storage).save(new Snapshot("active", "unverified-parent", 1,
                "syntax is valid", Map.of("file.txt", "unverified-object")));
        Files.writeString(storage.resolve("HEAD"), "active");
        Files.writeString(storage.resolve("snapshots/unreferenced.snapshot"), "unparsed metadata");
        Files.writeString(storage.resolve("objects/unverified-object"), "not checked against its name");

        RepositoryLayout.validate(storage);

        assertEquals("not checked against its name", Files.readString(storage.resolve("objects/unverified-object")));
        assertFalse(Files.exists(storage.resolve("snapshots/unverified-parent.snapshot")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedCommitMetadata_isValidatedWithoutPerformingRecovery(boolean headPublished) throws Exception {
        prepareInterruptedCommit(headPublished);
        Path index = storage.resolve("staging/index");
        Path pending = storage.resolve("staging/commit.pending");
        byte[] originalIndex = Files.readAllBytes(index);
        byte[] originalPending = Files.readAllBytes(pending);
        String originalHead = Files.readString(storage.resolve("HEAD"));

        RepositoryLayout.validate(storage);
        assertFalse(new RepositoryInitializer().initialize(root));

        assertArrayEquals(originalIndex, Files.readAllBytes(index));
        assertArrayEquals(originalPending, Files.readAllBytes(pending));
        assertEquals(originalHead, Files.readString(storage.resolve("HEAD")));
    }

    @Test
    void recoveryStateInconsistentWithStaging_isRejectedWithoutDiscardingWork() throws Exception {
        prepareInterruptedCommit(true);
        Path index = storage.resolve("staging/index");
        String changedIndex = MetadataCodec.encodeIndex(Map.of("changed.txt", "new-hash"));
        Files.writeString(index, changedIndex);
        byte[] pending = Files.readAllBytes(storage.resolve("staging/commit.pending"));

        IOException error = assertThrows(IOException.class, () -> RepositoryLayout.validate(storage));

        assertTrue(error.getMessage().contains("changed staging"));
        assertEquals(changedIndex, Files.readString(index));
        assertArrayEquals(pending, Files.readAllBytes(storage.resolve("staging/commit.pending")));
    }

    private void prepareInterruptedCommit(boolean headPublished) throws Exception {
        Path source = Files.writeString(root.resolve("file.txt"), "actual project contents");
        FileHasher.HashedFile file = new FileHasher().hashWithBytes(source);
        new ObjectStore(storage).store(file.hash(), file.bytes());
        new StagingArea(storage).stage(Path.of("file.txt"), file.hash());
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override
            protected void move(Path temporary, Path destination) throws IOException {
                if (!headPublished && destination.equals(storage.resolve("HEAD"))) {
                    throw new IOException("injected HEAD publication failure");
                }
                super.move(temporary, destination);
            }

            @Override
            public void delete(Path path) throws IOException {
                if (headPublished && path.equals(storage.resolve("staging/index"))) {
                    throw new IOException("injected index cleanup failure");
                }
                super.delete(path);
            }
        };
        CommitService commits = new CommitService(storage, publisher);
        if (headPublished) {
            assertNotNull(commits.commit("interrupted cleanup").warning());
        } else {
            assertThrows(IOException.class, () -> commits.commit("interrupted publication"));
        }
        assertTrue(Files.exists(storage.resolve("staging/commit.pending")));
    }
}
