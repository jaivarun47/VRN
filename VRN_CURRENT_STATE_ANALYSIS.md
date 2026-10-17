# VRN: Current State Analysis & Technical Audit Report

> **Document**: `VRN_CURRENT_STATE_ANALYSIS.md`  
> **Project Root**: `C:\Users\Oggy\IdeaProjects\VRN`  
> **Date of Audit**: October 12, 2026  
> **Auditor**: Antigravity Autonomous Pair Programmer  
> **Verification Basis**: Independent code inspection, Gradle test execution (`.\gradlew.bat test --rerun`), and JAR build verification (`.\gradlew.bat jar --rerun-tasks`).

---

## 1. Executive Summary

VRN is a Git-inspired, lightweight version control system developed in Java. It uses SHA-256 cryptographic content addressing, an atomic staging index, snapshot inheritance, and working-tree status classification.

Following recent development, VRN has advanced from an early prototype (`init` and naive `add`) to a functional core capable of:
1. Initializing repositories (`vrn init`).
2. Staging targeted paths with single-read hashing and atomic deduplicated blob storage (`vrn add <path>`).
3. Preserving and accumulating project history through parent-linked snapshots (`vrn commit -m "message"`).
4. Classifying working tree changes into five distinct states (`vrn status`).

### Key Independent Audit Findings

- **Test Suite Verification**: An independent execution of `.\gradlew.bat test --rerun` confirmed **32 tests across 2 test classes (`VrnCommitTest`: 18, `VrnStatusTest`: 14), with 0 failures, 0 errors, and 0 skipped tests**. The previous historical claim of 32 passing tests is **empirically verified**.
- **Build Verification**: `.\gradlew.bat jar --rerun-tasks` builds `build/libs/VRN-1.0-SNAPSHOT.jar` cleanly with executable main class `com.vrn.VrnApplication`.
- **Snapshot Inheritance Bug**: The previously identified snapshot inheritance bug—where commits would discard parent tracked files—has been **verified as resolved** in [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java#L118-L126). Each new snapshot loads the parent snapshot manifest from [SnapshotStore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/SnapshotStore.java) and overlays staged additions and modifications. Regression test `vrnignore_remainsTracked_afterSubsequentCommit` passes.
- **Double-Read TOCTOU Bug**: The file-scanning pipeline in [FileScanner.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileScanner.java) and [ScannedFile.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ScannedFile.java) has been updated to read raw bytes once during hashing and pass them directly to [ObjectStore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java).
- **Critical Architectural Gaps Discovered**:
  1. **Deletion Staging & Removal Semantics**: While [WorkingTreeStatus.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/WorkingTreeStatus.java) detects deleted tracked files on disk, VRN provides **no mechanism to stage a deletion**. Running `vrn add <deleted-file>` throws `IllegalArgumentException: Invalid path`. Because [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java) inherits all parent snapshot files and only overlays staged entries, **committed files can never be removed from subsequent snapshots**.
  2. **Write-Only ObjectStore**: [ObjectStore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java) possesses a `store(String hash, byte[] content)` method with atomic move, but **zero retrieval methods** (`read`, `get`, or `retrieve`). Repository restoration (`vrn restore`) cannot be implemented without adding an object read API.
  3. **Staging I/O Performance ($O(N^2)$)**: In [AddCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java), calling `stagingArea.stage()` inside a loop causes `StagingArea` to read and rewrite the entire `.vrn/staging/index` file on every single file, leading to quadratic disk operations.
  4. **Random UUID Commit IDs**: Snapshot IDs in [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java#L127-L129) are generated using `UUID.randomUUID()` rather than cryptographic content hashing of the tree and commit metadata.
  5. **Status Rehash Cost**: [WorkingTreeStatus.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/WorkingTreeStatus.java#L195-L217) walks and computes SHA-256 hashes for *every regular file* in the repository on every `vrn status` run, lacking an index stat cache (`mtime`/`size`).

---

## 2. Current Project Structure

### Repository File Map

```text
C:\Users\Oggy\IdeaProjects\VRN\
├── .gitignore                          # Standard git exclusions (.gradle, build, IDEs)
├── build.gradle                        # Gradle build script (Picocli, JUnit 6 platform)
├── gradlew / gradlew.bat               # Gradle wrapper scripts (Gradle 9.6.0)
├── settings.gradle                     # rootProject.name = 'VRN'
├── VRN_CURRENT_STATE_ANALYSIS.md       # Current analysis deliverable
├── VRN_SYSTEM_ANALYSIS.md              # Historical specification from earlier milestone
└── src/
    ├── main/java/com/vrn/
    │   ├── VrnApplication.java         # Picocli main CLI runner (registers subcommands)
    │   ├── cli/
    │   │   ├── AddCommand.java         # CLI handler for `vrn add <path>`
    │   │   ├── CommitCommand.java      # CLI handler for `vrn commit -m <msg>`
    │   │   ├── InitCommand.java        # CLI handler for `vrn init`
    │   │   └── StatusCommand.java      # CLI handler for `vrn status`
    │   └── core/
    │       ├── FileHasher.java         # SHA-256 digest computation & HashedFile record
    │       ├── FileScanner.java        # Path validation, traversal, ignore filtering
    │       ├── ObjectStore.java        # Content-addressed storage (.vrn/objects/<hash>)
    │       ├── ScannedFile.java        # Record: (Path path, String hash, byte[] bytes)
    │       ├── Snapshot.java           # Record: (id, parentId, timestamp, message, files)
    │       ├── SnapshotStore.java      # Parser and writer for .vrn/snapshots/<id>.snapshot
    │       ├── StagingArea.java        # Index manager (.vrn/staging/index)
    │       ├── VrnIgnore.java          # Parser & pattern evaluator for .vrnignore
    │       └── WorkingTreeStatus.java  # Differential status evaluator (disk vs index vs HEAD)
    └── test/java/com/vrn/
        ├── VrnCommitTest.java          # 18 JUnit 5 tests (commit workflow, store, staging)
        └── VrnStatusTest.java          # 14 JUnit 5 tests (status classification, regression)
```

### Class Responsibilities Reference

| Class / Record | Type | Responsibility |
| :--- | :--- | :--- |
| [VrnApplication](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/VrnApplication.java) | Class | CLI entry point. Configures Picocli annotations and dispatches subcommands: `init`, `add`, `commit`, `status`. |
| [InitCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/InitCommand.java) | Class | Sets up repository directories (`objects`, `snapshots`, `staging`), seeds `HEAD` (`"NONE"`), `config`, and `.vrnignore`. Applies DOS hidden attribute on Windows. |
| [AddCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java) | Class | CLI subcommand `add <path>`. Validates target path, scans files, writes blobs to `ObjectStore`, and registers path-to-hash mappings in `StagingArea`. |
| [CommitCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java) | Class | CLI subcommand `commit -m <msg>`. Validates staging non-emptiness, inherits parent snapshot files, overlays staging entries, generates random UUID snapshot ID, atomically writes snapshot file, advances `HEAD`, and clears staging. |
| [StatusCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/StatusCommand.java) | Class | CLI subcommand `status`. Calls `WorkingTreeStatus.compute()` and formats terminal output for staged additions, staged modifications, unstaged modifications, deletions, and untracked files. |
| [ScannedFile](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ScannedFile.java) | Record | Value object holding `(Path path, String hash, byte[] bytes)`. Bridges scanner to storage without redundant disk reads. |
| [FileHasher](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileHasher.java) | Class | Computes SHA-256 digests. Provides `hashWithBytes(Path)` to load content into memory once and generate a 64-character lowercase hexadecimal digest. |
| [FileScanner](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileScanner.java) | Class | Resolves and validates target paths against project root. Recursively walks directories or handles single files, filters non-regular files and `.vrnignore` matches, and returns `List<ScannedFile>`. |
| [ObjectStore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java) | Class | Writes immutable content blobs into `.vrn/objects/<hash>`. Skips write if object exists (deduplication). Writes to `<hash>.tmp` then renames with `ATOMIC_MOVE`. |
| [StagingArea](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java) | Class | Manages `.vrn/staging/index`. Supports `stage(Path, String)` (in-memory map update then rewrite), `readAll()` (returns `Map<String, String>`), and `clear()` (deletes index file). |
| [Snapshot](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/Snapshot.java) | Record | Immutable domain record: `(String id, String parentId, long timestamp, String message, Map<String, String> files)`. |
| [SnapshotStore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/SnapshotStore.java) | Class | Serializes snapshots to `.vrn/snapshots/<id>.snapshot` via temporary file and atomic rename. Parses `.snapshot` files into `Snapshot` records. |
| [VrnIgnore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java) | Class | Loads pattern lines from `.vrnignore`. Evaluates path strings against trailing-slash directory rules, wildcard rules, and exact filename matches. |
| [WorkingTreeStatus](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/WorkingTreeStatus.java) | Class | Pure computational engine comparing three states: working directory on disk, staging index, and HEAD snapshot manifest. Sorts and categorizes changes. |

---

## 3. Architecture and Data Flow

### Architecture Layers

```text
+-----------------------------------------------------------------------------------------+
|                                        CLI LAYER                                        |
|                          com.vrn.VrnApplication (Picocli)                               |
+-----------------------------------------------------------------------------------------+
       |                           |                          |                     |
       v                           v                          v                     v
+---------------+          +---------------+          +----------------+    +-------------+
|  InitCommand  |          |   AddCommand  |          | CommitCommand  |    |StatusCommand|
+---------------+          +---------------+          +----------------+    +-------------+
       |                           |                          |                     |
       |                           +---------------------+    |                     |
       |                           |                     |    |                     |
       v                           v                     v    v                     v
+-----------------------------------------------------------------------------------------+
|                                    CORE DOMAIN LAYER                                    |
|   FileScanner ──► FileHasher               SnapshotStore ◄──► Snapshot                  |
|        │               │                          │                                     |
|        v               v                          v                                     |
|   VrnIgnore       ScannedFile              WorkingTreeStatus                            |
+-----------------------------------------------------------------------------------------+
       |                   |                                |                        |
       v                   v                                v                        v
+-----------------------------------------------------------------------------------------+
|                              METADATA & STORAGE LAYER                                   |
|   ObjectStore (.vrn/objects/<hash>)       StagingArea (.vrn/staging/index)              |
|   SnapshotStore (.vrn/snapshots/<id>.snap)HEAD (.vrn/HEAD)                              |
+-----------------------------------------------------------------------------------------+
```

### Detailed Command Data Flows

#### 1. Data Flow: `vrn init`
```text
CLI (vrn init)
  │
  ├─► Check .vrn existence -> if exists, abort
  ├─► Files.createDirectories(.vrn/objects)
  ├─► Files.createDirectories(.vrn/snapshots)
  ├─► Files.createDirectories(.vrn/staging)
  ├─► Files.writeString(.vrn/HEAD, "NONE")
  ├─► Files.writeString(.vrn/config, "version=1")
  ├─► Files.writeString(.vrnignore, ".vrn/\n") (Warning: overwrites if existing)
  └─► DosFileAttributeView -> setHidden(true) on Windows
```

#### 2. Data Flow: `vrn add <path>`
```text
CLI (vrn add <path>)
  │
  ├─► FileScanner.scan(projectRoot, targetPath)
  │     ├─► Normalize target; reject if outside projectRoot or !exists
  │     ├─► Stream paths: target is directory ? Files.walk(target) : Stream.of(target)
  │     ├─► Filter: Files::isRegularFile
  │     ├─► Relativize to projectRoot
  │     ├─► Filter: !VrnIgnore.isIgnored(path)
  │     └─► FileHasher.hashWithBytes(file)
  │           ├─► Files.readAllBytes(file) [Single disk read]
  │           ├─► MessageDigest SHA-256
  │           └─► Return ScannedFile(path, hash, bytes)
  │
  └─► Loop over ScannedFiles:
        ├─► ObjectStore.store(hash, bytes)
        │     ├─► Check if .vrn/objects/<hash> exists -> return if true
        │     ├─► Write .vrn/objects/<hash>.tmp
        │     └─► Atomic rename: <hash>.tmp -> <hash>
        │
        └─► StagingArea.stage(path, hash)
              ├─► StagingArea.readAll() -> load Map<String, String> from .vrn/staging/index
              ├─► entries.put(normalizedPath, hash)
              └─► StagingArea.writeEntries() -> rewrite entire .vrn/staging/index
```

#### 3. Data Flow: `vrn commit -m "message"`
```text
CLI (vrn commit -m "message")
  │
  ├─► Validate commit message is non-blank
  ├─► Read .vrn/HEAD -> parentId (e.g. "NONE" or previous UUID)
  ├─► StagingArea.readAll() -> stagedFiles map; reject if empty
  │
  ├─► Initialize snapshotFiles = new LinkedHashMap<>()
  ├─► If parentId != "NONE":
  │     ├─► SnapshotStore.load(parentId)
  │     └─► snapshotFiles.putAll(parentSnapshot.files()) [Inherit parent tree]
  │
  ├─► snapshotFiles.putAll(stagedFiles) [Overlay staged additions/modifications]
  ├─► Generate snapshotId = UUID.randomUUID().toString().replace("-", "")
  ├─► Snapshot = new Snapshot(snapshotId, parentId, now, message, snapshotFiles)
  │
  ├─► SnapshotStore.save(snapshot)
  │     ├─► Serialize snapshot format into String
  │     ├─► Write .vrn/snapshots/<id>.snapshot.tmp
  │     └─► Atomic rename: <id>.snapshot.tmp -> <id>.snapshot
  │
  ├─► Files.writeString(.vrn/HEAD, snapshotId) [Advance HEAD pointer]
  └─► StagingArea.clear() [Delete .vrn/staging/index]
```

#### 4. Data Flow: `vrn status`
```text
CLI (vrn status)
  │
  ├─► WorkingTreeStatus.compute(projectRoot)
  │     ├─► StagingArea.readAll() -> Map<String, String> staged
  │     ├─► Read .vrn/HEAD -> if "NONE" committed = emptyMap; else SnapshotStore.load(headId).files()
  │     ├─► buildWorkingDirMap(projectRoot):
  │     │     ├─► Load .vrnignore
  │     │     ├─► Files.walk(projectRoot)
  │     │     ├─► Filter regular files, relativize, filter !ignore.isIgnored
  │     │     └─► For each file: hasher.hash(file) -> Map<String, String> workingDir
  │     │
  │     ├─► Compare states across knownPaths (staged ∪ committed):
  │     │     ├─► inStaging && !inCommitted            ──► stagedNewFiles
  │     │     ├─► inStaging && inCommitted && diffHash ──► stagedModifications
  │     │     ├─► inStaging && onDisk && diffStaged    ──► modifiedNotStaged (modified after stage)
  │     │     ├─► !inStaging && inCommitted && !onDisk ──► deleted (deleted tracked file)
  │     │     └─► !inStaging && inCommitted && diffDisk──► modifiedNotStaged
  │     │
  │     └─► Compare workingDir against knownPaths:
  │           └─► onDisk && !inStaging && !inCommitted  ──► untracked
  │
  └─► StatusCommand.printStatus(status) -> format categorized console output
```

---

## 4. Feature-by-Feature Implementation Status

| Feature | Audit Status | Code Location | Verification Evidence / Technical Details |
| :--- | :--- | :--- | :--- |
| **Repository Initialization** | **Implemented & Verified** | [InitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/InitCommand.java) | Creates `.vrn/objects`, `snapshots`, `staging`, `HEAD` (`"NONE"`), `config`, and `.vrnignore`. Tested in `VrnCommitTest` and `VrnStatusTest`. |
| **Targeted Path Staging** | **Implemented & Verified** | [AddCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java), [FileScanner.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileScanner.java) | `vrn add <path>` correctly resolves `<path>` against `projectRoot`. Rejects paths outside root or nonexistent paths. Tested in `fileScanner_scansFileAndReturnsBytes`. |
| **Single-Read Hashing** | **Implemented & Verified** | [FileHasher.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileHasher.java), [ScannedFile.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ScannedFile.java) | `hashWithBytes()` loads bytes once into memory, computes SHA-256, and returns `HashedFile(hash, bytes)`. Verified in `fileHasher_hashWithBytes_returnsConsistentHashAndBytes`. |
| **Atomic Object Store** | **Implemented & Verified** | [ObjectStore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java) | Writes raw bytes to `<hash>.tmp` then `StandardCopyOption.ATOMIC_MOVE` to `<hash>`. Skips write if object exists. Verified in `objectStore_storesContentByHash` and `objectStore_doesNotOverwriteExistingObject`. |
| **In-Memory Staging Index** | **Implemented & Verified** | [StagingArea.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java) | Parses `.vrn/staging/index` into `LinkedHashMap`, normalizes path separators to `/`, updates/re-stages entries, and rewrites index without duplicates. Verified in `stagingArea_restageUpdatesHash`. |
| **Parent Snapshot Inheritance** | **Implemented & Verified** | [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java#L118-L126) | Loads parent snapshot manifest when `parentId != "NONE"` and copies all tracked files before applying staged modifications. Verified in `vrnignore_remainsTracked_afterSubsequentCommit` and `commitWorkflow_secondCommitHasCorrectParent`. |
| **Snapshot Metadata Serialization** | **Implemented & Verified** | [SnapshotStore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/SnapshotStore.java) | Serializes key-value metadata and `[files]` section. Atomic write via `<id>.snapshot.tmp` and `ATOMIC_MOVE`. Verified in `snapshotStore_saveAndLoad_roundTrip`. |
| **HEAD Tracking** | **Implemented & Verified** | [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java#L140) | Reads parent ID from `.vrn/HEAD` and updates `HEAD` with the new snapshot ID upon successful snapshot creation. |
| **Staging Area Cleanup** | **Implemented & Verified** | [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java#L141), [StagingArea.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java#L59) | `stagingArea.clear()` deletes `.vrn/staging/index` upon successful commit. Verified in `stagingArea_clear_deletesIndex` and `commitWorkflow_happyPath`. |
| **Working-Tree Status Engine** | **Implemented & Verified** | [WorkingTreeStatus.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/WorkingTreeStatus.java) | Compares working directory against staging index and HEAD snapshot. Correctly identifies 5 states (staged new, staged mod, unstaged mod, deleted, untracked). Verified across 14 tests in `VrnStatusTest`. |
| **Basic Ignore Pattern Rules** | **Implemented & Verified** | [VrnIgnore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java) | Skips comments and empty lines; matches prefix/suffix wildcards and root-level directories ending in `/`. Verified in `ignoredFiles_notReportedInAnyCategory`. |
| **Deletion Staging (`vrn rm`)** | **Not Implemented** | N/A | No CLI command or sentinel value exists to stage a deletion. Files deleted from disk cannot be staged. |
| **Deletion Removal from Snapshots** | **Not Implemented** | [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java#L118-L125) | `CommitCommand` unconditionally inherits all parent files. Deleted files remain permanently in subsequent snapshots. |
| **ObjectStore Retrieval API** | **Not Implemented** | [ObjectStore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java) | `ObjectStore` has no `retrieve()` or `read()` method. Content cannot be extracted back out. |
| **Snapshot Restore (`vrn restore`)** | **Not Implemented** | N/A | No restore command or checkout engine exists. |
| **History Log (`vrn log`)** | **Not Implemented** | N/A | No command exists to traverse parent pointers and display historical commit graphs. |
| **Content-Hashed Commit IDs** | **Not Implemented** | [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java#L127) | Uses `UUID.randomUUID()` instead of SHA-256 hash of commit manifest. |
| **Advanced `.vrnignore` Syntax** | **Partially Implemented** | [VrnIgnore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java) | `**`, negation `!`, root `/`, and nested directories without explicit prefixes are unsupported. |
| **Automatic Versioning Engine** | **Not Implemented** | N/A | No `WatchService`, debouncing, or change significance evaluation exists. |

---

## 5. Test and Build Results

### 1. Test Suite Execution (`.\gradlew.bat test --rerun`)
- **Status**: **PASSED** (Build time: 9 seconds)
- **Total Test Classes**: 2
- **Total Tests Executed**: 32
- **Failures**: 0
- **Errors**: 0
- **Skipped**: 0

#### Test Breakdown by Class

```text
Class: com.vrn.VrnCommitTest (18 Tests, 0 Failures)
  [PASS] fileScanner_ignoresVrnDirectory()
  [PASS] commitWorkflow_happyPath()
  [PASS] stagingArea_pathSeparatorNormalized()
  [PASS] snapshotStore_parentIdPreserved_forFirstCommit()
  [PASS] fileScanner_scansFileAndReturnsBytes()
  [PASS] fileHasher_differentContent_producesDifferentHash()
  [PASS] objectStore_doesNotOverwriteExistingObject()
  [PASS] snapshotStore_saveAndLoad_roundTrip()
  [PASS] fileHasher_hashWithBytes_returnsConsistentHashAndBytes()
  [PASS] snapshotStore_snapshotFileNamedCorrectly()
  [PASS] snapshotStore_parentIdPreserved_forSubsequentCommit()
  [PASS] commitWorkflow_secondCommitHasCorrectParent()
  [PASS] stagingArea_readAll_emptyWhenNoIndex()
  [PASS] objectStore_storesContentByHash()
  [PASS] stagingArea_clear_deletesIndex()
  [PASS] snapshotStore_loadThrowsForUnknownId()
  [PASS] stagingArea_restageUpdatesHash()
  [PASS] stagingArea_stageAndReadAll()

Class: com.vrn.VrnStatusTest (14 Tests, 0 Failures)
  [PASS] stagedNewFile_reportedAsStagedNew()
  [PASS] modifiedTrackedFile_reportedAsModifiedNotStaged()
  [PASS] ignoredFiles_notReportedInAnyCategory()
  [PASS] noCommits_stagedFile_reportedAsStagedNew()
  [PASS] untrackedFile_reportedAsUntracked()
  [PASS] missingStagingIndex_treatedAsEmptyStaging()
  [PASS] vrnignore_remainsTracked_afterSubsequentCommit()  <-- Regression test verified!
  [PASS] stagedEntryWithoutDiskFile_stillAppearsAsStaged()
  [PASS] stagedModification_reportedAsStagedModified()
  [PASS] freshRepo_noCommitsNoStaging_isClean()
  [PASS] modifiedAgainAfterStaging_reportedInBothCategories()
  [PASS] cleanCommittedProject_isClean()
  [PASS] multipleFiles_onlyChangedOnesReported()
  [PASS] deletedTrackedFile_reportedAsDeleted()
```

### 2. JAR Packaging (`.\gradlew.bat jar --rerun-tasks`)
- **Status**: **SUCCESSFUL**
- **Output Artifact**: `build/libs/VRN-1.0-SNAPSHOT.jar` (Size: 425 KB)
- **Manifest Main-Class**: `com.vrn.VrnApplication`
- **CLI Subcommand Verification via Executable JAR**:
  - `java -jar build/libs/VRN-1.0-SNAPSHOT.jar` -> Prints `"VRN - Versioning for Developers"`.
  - Subcommands registered and recognized: `init`, `add`, `commit`, `status`.

---

## 6. Known Limitations and Risks

### 1. The Permanent Deletion Trap (High Architectural Risk)
Currently, VRN has **no concept of staging a file removal**:
- When a tracked file is deleted from disk, `vrn status` displays `deleted: <path>`.
- If the developer runs `vrn add <path>`, `FileScanner.scan()` throws `IllegalArgumentException: Invalid path` because `Files.exists(target)` evaluates to false.
- In [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java#L121-L125):
  ```java
  if (!parentId.equals("NONE")) {
      Snapshot parentSnapshot = snapshotStore.load(parentId);
      snapshotFiles.putAll(parentSnapshot.files());
  }
  snapshotFiles.putAll(stagedFiles);
  ```
  Because the staging index cannot record a deletion sentinel, the parent snapshot's tracked file is **always inherited**. Once a file is committed, it can **never be removed from future snapshots**.

### 2. Phantom Staged Files when Deleted Pre-Commit
If a file is added with `vrn add newfile.txt` and then deleted from disk before `vrn commit`:
- `WorkingTreeStatus` continues to report `newfile.txt` as `staged new file` (as verified in `stagedEntryWithoutDiskFile_stillAppearsAsStaged`).
- When `vrn commit` is executed, `newfile.txt` is recorded in the snapshot manifest, even though it no longer exists on disk.

### 3. Missing Object Retrieval API
[ObjectStore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java) provides only:
```java
public void store(String hash, byte[] content) throws Exception
```
There is no `byte[] retrieve(String hash)` or `InputStream openStream(String hash)`. VRN can currently write objects into the object database, but cannot read them back out to reconstruct working directory files.

### 4. Quadratic Disk I/O during `vrn add`
In [AddCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java#L45-L49):
```java
for (ScannedFile file : files) {
    objectStore.store(file.hash(), file.bytes());
    stagingArea.stage(file.path(), file.hash());
}
```
Every single call to `stagingArea.stage()` invokes `readAll()` (reads the entire index file) and `writeEntries()` (rewrites the entire index file). For $N$ files, this results in $2N$ disk I/O operations and $O(N^2)$ data volume rewritten. Staging a 1,000-file project causes 1,000 file reads and 1,000 file overwrites.

### 5. High-Cost Working Tree Status Rehashing
In [WorkingTreeStatus.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/WorkingTreeStatus.java#L209-L213):
```java
for (Path rel : regularFiles) {
    String normalized = rel.toString().replace('\\', '/');
    String hash = hasher.hash(projectRoot.resolve(rel));
    result.put(normalized, hash);
}
```
`vrn status` calculates full SHA-256 digests for every file in the repository every time it runs. Unlike Git (which inspects file size and timestamp `mtime` cached in the index to avoid hashing unchanged files), VRN reads and hashes all project files synchronously.

### 6. Non-Deterministic, Random Snapshot IDs
In [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java#L127-L129):
```java
String snapshotId = UUID.randomUUID()
        .toString()
        .replace("-", "");
```
Snapshot IDs are random UUIDs rather than SHA-256 hashes of the snapshot content. This deviates from Git's Merkle tree model, prevents deterministic commit verification, and prevents cryptographic tamper detection.

### 7. Existing `.vrnignore` Overwrite on `init`
In [InitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/InitCommand.java#L45):
```java
Files.writeString(ignoreFile, ".vrn/\n");
```
If `.vrnignore` already exists in the project root prior to running `vrn init`, it is overwritten and truncated without prompt. Conversely, if `.vrnignore` is deleted after initialization, `FileScanner` and `WorkingTreeStatus` crash with `NoSuchFileException`.

### 8. CLI Subcommand Help Options Missing
Subcommands `init`, `add`, `commit`, and `status` lack `@Command(mixinStandardHelpOptions = true)`. Passing `--help` to any subcommand triggers a Picocli syntax error (e.g., `Unknown option: '--help'` or `Missing required option: '--message=<message>'`).

---

## 7. Recommended Next Milestone

### Milestone: Complete the Core Lifecycle with `vrn restore` and Deletion Tracking (`vrn rm`)

Before moving toward automatic versioning or background filesystem monitoring, the fundamental version control lifecycle must be closed. A version control system that can stage, commit, and inspect status—but **cannot restore files** and **cannot delete files from history**—is incomplete.

```text
Current State:
  vrn init  ──►  vrn add  ──►  vrn commit  ──►  vrn status  ──►  [CANNOT RESTORE OR DELETE]

Target Milestone:
  vrn init  ──►  vrn add / rm  ──►  vrn commit  ──►  vrn status  ──►  vrn restore <snapshot-id>
```

### Components Required for the Next Milestone

1. **ObjectStore Retrieval API**:
   - Add `byte[] retrieve(String hash)` and `boolean exists(String hash)` to [ObjectStore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java).
2. **Deletion Staging & `vrn rm <path>`**:
   - Update [StagingArea.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java) to support deletion tombstones (e.g., `<path>=DELETED` or a distinct deleted-paths index).
   - Implement `RmCommand` (`vrn rm <path>`) that stages file removal and optionally removes the file from the working tree.
   - Update [CommitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/CommitCommand.java) so that staged deletions remove the entry from the inherited parent snapshot manifest.
3. **Snapshot Restoration Engine (`vrn restore <snapshot-id>`)**:
   - Implement `RestoreCommand` (`vrn restore <snapshot-id>`).
   - Validate working tree cleanliness (prevent clobbering uncommitted modifications).
   - Load snapshot manifest from `SnapshotStore`.
   - Reconstruct working directory files by fetching blobs from `ObjectStore`.
   - Remove files present on disk that are absent from the target snapshot.
   - Update `HEAD` to point to the restored snapshot.
4. **Batch Staging Optimization**:
   - Refactor `AddCommand` to collect all `ScannedFile` entries and stage them in a single batch (`stagingArea.stageAll(Map<String, String>)`), resolving the $O(N^2)$ I/O bottleneck.

---

## 8. Summary Table: Verified vs. Pending Features

| Capability | Current State | Verification Status | Next Action |
| :--- | :--- | :--- | :--- |
| **`vrn init`** | Working | Verified (JUnit & Manual) | Guard `.vrnignore` against overwrite |
| **`vrn add <path>`** | Working | Verified (JUnit & Manual) | Optimize batch staging ($O(N)$) |
| **`vrn commit -m`** | Working | Verified (18 Tests) | Transition snapshot ID from UUID to SHA-256 |
| **`vrn status`** | Working | Verified (14 Tests) | Add stat-cache (`mtime`/`size`) to avoid rehashing |
| **Snapshot Inheritance** | Working | Verified (Regression test passing) | Handle staged deletion overlay |
| **`vrn rm <path>`** | Missing | Not Implemented | Create `RmCommand` & tombstone staging |
| **`vrn restore`** | Missing | Not Implemented | Create `RestoreCommand` & `ObjectStore.retrieve` |
| **`vrn log`** | Missing | Not Implemented | Create `LogCommand` to traverse commit history |
| **Auto-Versioning** | Missing | Not Implemented | Defer until Core Lifecycle is complete |
