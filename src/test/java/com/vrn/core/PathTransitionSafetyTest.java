package com.vrn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathTransitionSafetyTest {
    @TempDir Path root;

    PathTransitionTest prepare(String kind) throws Exception {
        var fixture = new PathTransitionTest();
        fixture.root = root;
        fixture.prepare(kind);
        return fixture;
    }

    @Test void mixedCaseUnstageAliasRequiresExactRecordSpellingWhilePairExists() throws Exception {
        var fixture = prepare("file-case");
        new AddService(root).add(Path.of("HELLO.TXT"));
        byte[] before = Files.readAllBytes(fixture.index);
        assertThrows(IOException.class, () -> new StagingArea(fixture.storage).unstage(Path.of("HeLlO.TxT")));
        assertArrayEquals(before, Files.readAllBytes(fixture.index));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pendingCommitCleanupConsumesTransitionOnceAndNeverConsumesFreshIntent(boolean pendingDelete) throws Exception {
        var fixture = prepare("file-case");
        new AddService(root).add(Path.of("HELLO.TXT"));
        Path blocked = pendingDelete ? fixture.storage.resolve("staging/commit.pending") : fixture.index;
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override public void delete(Path path) throws IOException {
                if (path.equals(blocked)) throw new IOException("injected transition cleanup failure");
                super.delete(path);
            }
        };
        assertNotNull(new CommitService(fixture.storage, publisher).commit("published transition").warning());
        assertTrue(new StagingArea(fixture.storage).readOperations().isEmpty());
        assertTrue(WorkingTreeStatus.compute(root).isClean());
        fixture.write("keep.txt", "fresh after published transition");
        new AddService(root).add(Path.of("keep.txt"));
        byte[] fresh = Files.readAllBytes(fixture.index);
        new StagingArea(fixture.storage).recover();
        assertArrayEquals(fresh, Files.readAllBytes(fixture.index));
        assertFalse(Files.exists(fixture.storage.resolve("staging/commit.pending")));
    }

    @Test void changedRawFingerprintBlocksCleanupAndAddWithoutDiscardingNewerBytes() throws Exception {
        var fixture = prepare("file-case");
        new AddService(root).add(Path.of("HELLO.TXT"));
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override public void delete(Path path) throws IOException {
                if (path.equals(fixture.index)) throw new IOException("injected blocked cleanup");
                super.delete(path);
            }
        };
        assertNotNull(new CommitService(fixture.storage, publisher).commit("published transition").warning());
        String newer = MetadataCodec.encodeOperations(Map.of("keep.txt", new StagedOperation.Delete()));
        Files.writeString(fixture.index, newer);
        assertThrows(IOException.class, () -> new AddService(root).add(Path.of("keep.txt")));
        assertEquals(newer, Files.readString(fixture.index));
        assertTrue(Files.exists(fixture.storage.resolve("staging/commit.pending")));
    }

    @Test void confirmedIndexPublicationWithReportedErrorRetainsCompleteTransition() throws Exception {
        var fixture = prepare("file-case");
        AtomicFilePublisher publisher = new AtomicFilePublisher() {
            @Override protected void move(Path temporary, Path destination) throws IOException {
                super.move(temporary, destination);
                throw new IOException("injected error after complete transition publication");
            }
        };
        var result = new AddService(root, new FileScanner(), new ObjectStore(fixture.storage), publisher).add(Path.of("HELLO.TXT"));
        assertNotNull(result.warning());
        var operations = new StagingArea(fixture.storage).readOperations();
        assertInstanceOf(StagedOperation.Delete.class, operations.get("hello.txt"));
        assertInstanceOf(StagedOperation.Put.class, operations.get("HELLO.TXT"));
        new CommitService(fixture.storage).commit("complete confirmed transition");
        assertTrue(WorkingTreeStatus.compute(root).isClean());
    }

    @Test void caseFoldingAloneCannotProveMissingCaseSensitiveFileIdentity() throws Exception {
        new RepositoryInitializer().initialize(root);
        Path directory = Files.createDirectory(root.resolve("Sensitive"));
        WindowsStatusIdentityTest.sensitive(directory);
        Files.writeString(directory.resolve("Name.txt"), "original");
        new AddService(root).add(Path.of("."));
        new CommitService(root.resolve(".vrn")).commit("sensitive original");
        Files.delete(directory.resolve("Name.txt"));
        Files.writeString(directory.resolve("NAME.TXT"), "new case-sensitive entry");
        byte[] before = Files.exists(root.resolve(".vrn/staging/index")) ? Files.readAllBytes(root.resolve(".vrn/staging/index")) : null;
        assertThrows(IOException.class, () -> new AddService(root).add(Path.of("Sensitive/NAME.TXT")));
        assertArrayEquals(before, Files.exists(root.resolve(".vrn/staging/index")) ? Files.readAllBytes(root.resolve(".vrn/staging/index")) : null);
    }

    @ParameterizedTest @ValueSource(strings = {".vrn", ".vrnauto", "../outside", "NUL", "wrong:name"})
    void invalidOrReservedSelectionDoesNotPublishTransition(String path) throws Exception {
        var fixture = prepare("file-case");
        byte[] before = Files.readAllBytes(fixture.index);
        assertThrows(Exception.class, () -> new AddService(root).add(Path.of(path)));
        assertArrayEquals(before, Files.readAllBytes(fixture.index));
    }
}
