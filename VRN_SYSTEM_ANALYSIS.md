# VRN: Comprehensive System Architecture, Codebase Audit, and Technical Specification

> **Repository**: [jaivarun47/VRN](file:///c:/Users/Oggy/IdeaProjects/VRN)  
> **Environment**: Java 26.0.2 SE | Gradle 9.6.0 | Picocli 4.7.7 | Windows 11 (NTFS)  
> **Analysis Date**: October 2026  
> **Document Purpose**: Authoritative, code-verified technical specification and audit of the VRN codebase as implemented.

---

## Table of Contents

1. [Executive Summary & Current Project State](#1-executive-summary--current-project-state)
2. [Project Overview & Core Philosophy](#2-project-overview--core-philosophy)
3. [Implementation Status Audit Matrix](#3-implementation-status-audit-matrix)
4. [Complete System Architecture](#4-complete-system-architecture)
5. [Traced Execution Flows & Data Paths](#5-traced-execution-flows--data-paths)
6. [Repository & `.vrn` Internal Layout](#6-repository--vrn-internal-layout)
7. [File Scanning Engine](#7-file-scanning-engine)
8. [`.vrnignore` Pattern Matching Engine](#8-vrnignore-pattern-matching-engine)
9. [Cryptographic Hashing (SHA-256)](#9-cryptographic-hashing-sha-256)
10. [Content-Addressed Object Storage](#10-content-addressed-object-storage)
11. [Staging Area & Index Mechanism](#11-staging-area--index-mechanism)
12. [CLI Interface & Command Layer](#12-cli-interface--command-layer)
13. [Snapshot & Commit Architecture (Prerequisites & Gap Analysis)](#13-snapshot--commit-architecture-prerequisites--gap-analysis)
14. [Restoration & Checkout Architecture](#14-restoration--checkout-architecture)
15. [System Evolution Roadmap](#15-system-evolution-roadmap)
16. [Automatic Versioning Engine Specification](#16-automatic-versioning-engine-specification)
17. [Research Methodology & Evaluation Framework](#17-research-methodology--evaluation-framework)
18. [Technology Stack Audit](#18-technology-stack-audit)
19. [Testing & Verification Audit](#19-testing--verification-audit)
20. [Software Engineering Quality Review](#20-software-engineering-quality-review)
21. [Critical Architectural & Design Risks](#21-critical-architectural--design-risks)
22. [Recommended Next Implementation Milestone](#22-recommended-next-implementation-milestone)
23. [Final System Status Reference Block](#23-final-system-status-reference-block)

---

## 1. Executive Summary & Current Project State

VRN is a Git-inspired, content-addressed version-control and autonomous snapshot system built in Java. Its foundational data model uses cryptographic SHA-256 content addressing, an append-only staging index, and a hidden `.vrn` metadata directory.

```text
+-----------------------------------------------------------------------------------------+
|                               VRN CURRENT SYSTEM STATUS                                 |
+-------------------+---------------------------------------------------------------------+
| Current Phase     | Stage 1: Core Version Control (Pre-Commit Prototype)                |
| Build Platform    | Gradle 9.6.0, Oracle JDK 26.0.2 (Java 26 SE)                        |
| CLI Engine        | Picocli 4.7.7                                                       |
| Source Classes    | 9 Java source files (2 CLI commands, 7 core components/records)    |
| Implemented CLI   | `vrn init`, `vrn add <path>` (Note: <path> parameter is ignored)    |
| Object Store      | Flat SHA-256 content-addressable storage (.vrn/objects/<hash>)      |
| Staging Area      | Append-only key-value text file (.vrn/staging/index)                |
| Snapshots/Commits | NOT IMPLEMENTED (.vrn/snapshots directory & HEAD file created only)|
| Restoration       | NOT IMPLEMENTED (ObjectStore has no read/retrieve method)           |
| Auto-Versioning   | NOT IMPLEMENTED (No daemon, no WatchService, no change analysis)    |
| Test Suite        | 0 test files (src/test/java is completely empty; test NO-SOURCE)    |
+-------------------+---------------------------------------------------------------------+
```

---

## 2. Project Overview & Core Philosophy

### What VRN Is
VRN is a version-control system designed to bridge the gap between traditional manual checkpointing (e.g., Git) and automated state preservation. It implements conventional content-addressable storage while serving as an experimental bed for autonomous, change-aware versioning.

### The Problem It Solves
Traditional version control is entirely **manual and intent-driven**:
1. **Cognitive Overhead**: Developers frequently forget to checkpoint functional intermediate states while debugging or prototyping.
2. **Coarse-Grained History**: Hours of iterative coding are often squashed into a single manual commit, discarding the evolution of the solution.
3. **Naïve Autosave Shortcomings**: Existing automated solutions (e.g., periodic autosave or backup plugins) produce excessive noise, capturing half-typed syntax errors and consuming unbounded storage.

### Why It Exists and Research Differentiation
VRN is designed to evolve beyond timer-based or save-based triggers by introducing **change-significance evaluation**:

```text
Conventional VCS (Git)   : Developer-driven  ==> Manual Stage  ==> Manual Commit
Naïve Auto-Snapshot       : Timer / Event     ==> Every 5 mins / Every Save (High Noise)
VRN (Eventual Vision)     : Significance-driven ==> Change Analysis ==> Smart Autonomous Snapshot
```

### Eventual Research Contribution
The core research question of the project is:
> *How effectively can change-aware snapshot policies preserve meaningful development states while reducing unnecessary snapshots, storage consumption, and processing overhead compared with conventional or naïve automatic snapshot strategies?*

VRN aims to provide empirical benchmarks comparing:
- Fixed-interval periodic snapshotting ($N$ minutes).
- Event-triggered snapshotting (on-save or file-system write bursts).
- **Change-aware significance policies** (AST delta, compile/test milestones, dependency tree modifications, and weighted threshold scoring).

---

## 3. Implementation Status Audit Matrix

Every capability in this matrix reflects the scanned code in the repository:

| Component / Feature | Status | Actual Implementation Reality |
| :--- | :--- | :--- |
| **CLI Dispatcher** | **IMPLEMENTED** | [VrnApplication.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/VrnApplication.java) registers `init` and `add`. |
| **Repository Initialization** | **IMPLEMENTED** | [InitCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/InitCommand.java) creates `.vrn/`, subdirectories, `HEAD`, `config`, and `.vrnignore`. |
| **Recursive File Traversal** | **IMPLEMENTED** | [FileScanner.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileScanner.java) uses `Files.walk`. |
| **Ignore Pattern Engine** | **PARTIALLY IMPLEMENTED** | [VrnIgnore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java) supports exact names, single `*`, and trailing slash directories. Does not support `**`, `!`, or nested directory rules. |
| **SHA-256 Content Hashing** | **IMPLEMENTED** | [FileHasher.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileHasher.java) computes full 64-character hex digests. |
| **Content-Addressed Storage** | **PARTIALLY IMPLEMENTED** | [ObjectStore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java) writes raw objects to `.vrn/objects/<hash>`. No compression, no read/retrieve API. |
| **Staging Area** | **PARTIALLY IMPLEMENTED** | [StagingArea.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java) appends `path=hash` lines to `.vrn/staging/index`. No deduplication or index reading. |
| **Targeted Path Staging** | **PARTIALLY IMPLEMENTED** | [AddCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java) accepts `<path>` parameter via Picocli but ignores it and stages the whole repository. |
| **Snapshots / Commits** | **PLANNED** | Directories exist, but no `CommitCommand`, commit metadata, commit objects, or history graph exist. |
| **Repository Restore / Checkout** | **PLANNED** | No restoration logic, no tree-manifest builder, no checkout command. |
| **Filesystem Monitoring** | **PLANNED** | No `WatchService`, daemon, or background file observer exists. |
| **Change Significance Engine**| **FUTURE / RESEARCH** | No AST diffing, metrics collectors, or heuristic threshold engines exist. |
| **AI / Semantic Analysis** | **FUTURE / RESEARCH** | Conceptual extension only. |

---

## 4. Complete System Architecture

### Architectural Layering

```text
+-----------------------------------------------------------------------+
|                              CLI LAYER                                |
|          com.vrn.VrnApplication (Picocli Entry Point)                 |
+-----------------------------------+-----------------------------------+
                                    |
                                    v
+-----------------------------------------------------------------------+
|                      APPLICATION / COMMAND LAYER                      |
|       com.vrn.cli.InitCommand   |   com.vrn.cli.AddCommand            |
+-------------------+---------------+-------------------+---------------+
                    |                                   |
                    v                                   v
+-----------------------------------------------------------------------+
|                    CORE VERSION-CONTROL COMPONENTS                    |
|       com.vrn.core.FileScanner   <---->  com.vrn.core.VrnIgnore       |
|                 |                                                     |
|                 v                                                     |
|       com.vrn.core.FileHasher    ---->   com.vrn.core.ScannedFile     |
+-------------------+-----------------------------------+---------------+
                    |                                   |
                    v                                   v
+-----------------------------------+   +-------------------------------+
|      CONTENT STORAGE LAYER        |   |        METADATA LAYER         |
|     com.vrn.core.ObjectStore      |   |   com.vrn.core.StagingArea    |
|   (.vrn/objects/<sha256-hash>)    |   |     (.vrn/staging/index)      |
+-------------------+---------------+   +---------------+---------------+
                    |                                   |
                    +-----------------+-----------------+
                                      |
                                      v
+-----------------------------------------------------------------------+
|                          FILESYSTEM (OS / NIO.2)                      |
|          Working Directory Files  |  .vrn Internal Storage            |
+-----------------------------------------------------------------------+
```

### Detailed Component Reference

#### 1. [VrnApplication](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/VrnApplication.java)
- **Package**: `com.vrn`
- **Responsibility**: Root command runner and CLI entry point.
- **Fields**: None. Annotated with `@Command(name = "vrn", subcommands = {InitCommand.class, AddCommand.class})`.
- **Methods**:
  - `run()`: Prints banner `"VRN - Versioning for Developers"`.
  - `main(String[] args)`: Dispatches CLI arguments through Picocli's `CommandLine.execute(args)`.
- **Inputs**: `String[] args` from terminal invocation.
- **Outputs**: System process exit code.
- **Dependencies**: `picocli.CommandLine`, [InitCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/InitCommand.java), [AddCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java).
- **Design Rationale**: Provides a single executable binary entry point.

#### 2. [InitCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/InitCommand.java)
- **Package**: `com.vrn.cli`
- **Responsibility**: Initializes physical repository structure on disk.
- **Fields**: None. Annotated with `@Command(name = "init", description = "Initialize a VRN repository")`.
- **Methods**:
  - `run()`: Creates `.vrn/`, `.vrn/objects/`, `.vrn/snapshots/`, `.vrn/staging/`, initializes `.vrn/HEAD` to `"NONE"`, `.vrn/config` to `"version=1"`, and `.vrnignore` to `".vrn/\n"`.
- **Inputs**: Working directory context (`Path.of("."):`).
- **Outputs**: Physical directories and files on disk.
- **Dependencies**: `java.nio.file.Files`, `java.nio.file.Path`, `java.nio.file.attribute.DosFileAttributeView`.
- **Design Rationale**: Establishes repository prerequisites before any tracking can begin.

#### 3. [AddCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java)
- **Package**: `com.vrn.cli`
- **Responsibility**: Application service coordinating file scanning, hashing, object persistence, and staging.
- **Fields**:
  - `@Parameters(index = "0") private Path path`: CLI argument representing target path (currently ignored).
- **Methods**:
  - `run()`: Resolves repository root, verifies repository existence, calls `FileScanner`, loops over discovered files, writes blobs to `ObjectStore`, and appends entries to `StagingArea`.
- **Inputs**: Working directory file system state.
- **Outputs**: Blob files in `.vrn/objects` and index entries in `.vrn/staging/index`.
- **Dependencies**: [FileScanner](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileScanner.java), [ObjectStore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java), [StagingArea](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java), [ScannedFile](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ScannedFile.java).
- **Design Rationale**: Serves as the high-level orchestration pipeline for ingestion.

#### 4. [ScannedFile](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ScannedFile.java)
- **Package**: `com.vrn.core`
- **Responsibility**: Immutable value object holding a file's project-relative path and computed SHA-256 hash.
- **Fields**: `Path path`, `String hash`.
- **Methods**: Standard Java record accessors (`path()`, `hash()`).
- **Inputs**: Generated by `FileScanner`.
- **Outputs**: Consumed by `AddCommand`.
- **Dependencies**: `java.nio.file.Path`.
- **Design Rationale**: Decouples directory traversal from storage operations.

#### 5. [FileScanner](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileScanner.java)
- **Package**: `com.vrn.core`
- **Responsibility**: Recursively traverses the workspace, filters exclusions, hashes contents, and returns tracked files.
- **Fields**: `private final FileHasher hasher = new FileHasher();`
- **Methods**:
  - `scan(Path projectRoot)`: Walks project tree via `Files.walk`, excludes non-regular files, relativizes paths, checks `VrnIgnore.isIgnored()`, hashes content, and returns `List<ScannedFile>`.
- **Inputs**: Absolute or relative `Path projectRoot`.
- **Outputs**: `List<ScannedFile>`.
- **Dependencies**: [VrnIgnore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java), [FileHasher](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileHasher.java), [ScannedFile](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ScannedFile.java).
- **Design Rationale**: Centralizes workspace discovery logic.

#### 6. [VrnIgnore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java)
- **Package**: `com.vrn.core`
- **Responsibility**: Parses `.vrnignore` patterns and filters workspace paths.
- **Fields**: `private final List<String> rules`: Parsed rule strings.
- **Methods**:
  - `VrnIgnore(Path ignoreFile)`: Loads and sanitizes lines from disk.
  - `isIgnored(Path path)`: Evaluates path against directory and wildcard rules.
  - `matches(String rule, String path)`: Helper matching prefix/suffix wildcards and exact filenames.
- **Inputs**: `Path ignoreFile` for rules; `Path` for query.
- **Outputs**: `boolean` (`true` if path matches exclusion rule).
- **Dependencies**: `java.nio.file.Files`, `java.nio.file.Path`.
- **Design Rationale**: Prevents temporary files, build outputs, and internal metadata from polluting the object store.

#### 7. [FileHasher](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileHasher.java)
- **Package**: `com.vrn.core`
- **Responsibility**: Computes cryptographic SHA-256 digests from file content.
- **Fields**: None.
- **Methods**:
  - `hash(Path file)`: Reads file bytes, computes digest, and converts to lowercase hex.
  - `bytesToHex(byte[] bytes)`: Encodes byte array into a 64-character hex string.
- **Inputs**: `Path file` pointing to a local file.
- **Outputs**: Lowercase 64-character hex string.
- **Dependencies**: `java.security.MessageDigest`, `java.nio.file.Files`.
- **Design Rationale**: Generates immutable content identifiers for content addressing.

#### 8. [ObjectStore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java)
- **Package**: `com.vrn.core`
- **Responsibility**: Manages the persistence of immutable blob objects in `.vrn/objects/`.
- **Fields**: `private final Path objectsDirectory`: Path to `.vrn/objects`.
- **Methods**:
  - `store(Path file, String hash)`: Verifies if `.vrn/objects/<hash>` exists; if not, writes raw file bytes to disk.
- **Inputs**: Source file `Path` and precomputed `String hash`.
- **Outputs**: Physical file at `.vrn/objects/<hash>`.
- **Dependencies**: `java.nio.file.Files`, `java.nio.file.Path`.
- **Design Rationale**: Implements content-addressed deduplication.

#### 9. [StagingArea](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java)
- **Package**: `com.vrn.core`
- **Responsibility**: Appends staged path-to-hash mappings to the staging index.
- **Fields**: `private final Path stagingFile`: Points to `.vrn/staging/index`.
- **Methods**:
  - `stage(Path path, String hash)`: Formats `<rel_path>=<hash>` and appends it to `.vrn/staging/index`.
- **Inputs**: Relative `Path path`, `String hash`.
- **Outputs**: Appends text to `.vrn/staging/index`.
- **Dependencies**: `java.nio.file.Files`, `java.nio.file.Path`.
- **Design Rationale**: Records the staging manifest in preparation for snapshot creation.

---

## 5. Traced Execution Flows & Data Paths

### 5.1 Call Flow: `vrn init`

```text
[CLI User]
    │
    ▼
VrnApplication.main(args)
    │  picocli.CommandLine invokes InitCommand
    ▼
InitCommand.run()
    │
    ├─► Path currentDirectory = Path.of(".");
    ├─► Path vrnDirectory = currentDirectory.resolve(".vrn");
    ├─► Files.exists(vrnDirectory)
    │     └─► [If true] Prints "VRN repository already exists." and returns.
    │
    ├─► Files.createDirectories(".vrn/objects")
    ├─► Files.createDirectories(".vrn/snapshots")
    ├─► Files.createDirectories(".vrn/staging")
    │
    ├─► Files.createFile(".vrn/HEAD")
    ├─► Files.createFile(".vrn/config")
    │
    ├─► Files.writeString(".vrn/HEAD", "NONE")
    ├─► Files.writeString(".vrn/config", "version=1")
    ├─► Files.writeString(".vrnignore", ".vrn/\n")
    │
    ├─► Files.getFileAttributeView(vrnDirectory, DosFileAttributeView.class)
    │     └─► attributes.setHidden(true)  (Applies DOS hidden attribute on Windows)
    │
    └─► System.out.println("Initialized empty VRN repository.")
```

---

### 5.2 Call Flow: `vrn add .`

```text
[CLI User]
    │
    ▼
VrnApplication.main(args)
    │  picocli.CommandLine binds args[0] to AddCommand.path
    ▼
AddCommand.run()
    │
    ├─► Path projectRoot = Path.of(".").toAbsolutePath().normalize();
    ├─► Files.exists(projectRoot.resolve(".vrn"))
    │     └─► [If false] Prints "Not a VRN repository." and returns.
    │
    ├─► FileScanner scanner = new FileScanner();
    ├─► ObjectStore objectStore = new ObjectStore(projectRoot.resolve(".vrn"));
    ├─► StagingArea stagingArea = new StagingArea(projectRoot.resolve(".vrn"));
    │
    ├─► FileScanner.scan(projectRoot)
    │     │
    │     ├─► Path ignoreFile = projectRoot.resolve(".vrnignore");
    │     ├─► VrnIgnore ignore = new VrnIgnore(ignoreFile);
    │     │     └─► Files.readAllLines(ignoreFile) -> clean and filter -> rules list
    │     │
    │     ├─► Files.walk(projectRoot)  [Stream<Path>]
    │     │     ├─► filter(Files::isRegularFile)
    │     │     ├─► map(projectRoot::relativize)
    │     │     ├─► filter(!ignore.isIgnored(path))
    │     │     │     └─► Evaluates directory rules and prefix/suffix wildcards
    │     │     │
    │     │     └─► map(path -> {
    │     │           hasher.hash(projectRoot.resolve(path))
    │     │             ├─► Files.readAllBytes(file)
    │     │             ├─► MessageDigest.getInstance("SHA-256").digest(bytes)
    │     │             └─► bytesToHex(hashBytes) -> 64-char hex string
    │     │           return new ScannedFile(path, hash);
    │     │         })
    │     └─► toList() -> List<ScannedFile>
    │
    ├─► Loop: for (ScannedFile file : files)
    │     │
    │     ├─► Path actualFile = projectRoot.resolve(file.path());
    │     │
    │     ├─► ObjectStore.store(actualFile, file.hash())
    │     │     ├─► Path objectPath = objectsDirectory.resolve(hash);
    │     │     ├─► if (Files.exists(objectPath)) return;  (Deduplication check)
    │     │     ├─► byte[] content = Files.readAllBytes(actualFile);  (Second disk read)
    │     │     └─► Files.write(objectPath, content);
    │     │
    │     └─► StagingArea.stage(file.path(), file.hash())
    │           ├─► Files.createDirectories(stagingFile.getParent());
    │           ├─► String entry = path.toString().replace('\\', '/') + "=" + hash + "\n";
    │           └─► Files.writeString(stagingFile, entry, CREATE, APPEND);
    │
    └─► System.out.println("Files staged successfully.")
```

### Divergences: Conceptual Architecture vs. Actual Code

```text
Documentation / Conceptual Expectation:
- vrn add <path> stages ONLY the specified <path> (file or directory).
- Scanner reads file once into memory, computes hash, and passes bytes directly to storage.
- StagingArea maintains an in-memory Map<Path, Hash> index, replacing changed entries.

Actual Code Implementation:
- In AddCommand.java, the <path> parameter is bound by Picocli but NEVER USED.
  FileScanner.scan(projectRoot) is hardcoded, scanning the ENTIRE repository root every time!
- Double Disk Read:
  1. FileScanner.scan() calls FileHasher.hash(), which calls Files.readAllBytes().
  2. AddCommand calls ObjectStore.store(), which calls Files.readAllBytes() a SECOND time.
- StagingArea is an append-only flat log:
  Running `vrn add .` repeatedly appends duplicate entries into `.vrn/staging/index` indefinitely.
```

---

## 6. Repository & `.vrn` Internal Layout

```text
<projectRoot>/
├── .vrnignore                      <-- Project-level exclusion patterns
└── .vrn/                           <-- Internal VCS metadata (hidden attribute set on Windows)
    ├── config                      <-- Repository configuration file
    ├── HEAD                        <-- Pointer to current snapshot
    ├── objects/                    <-- Content-addressed object store
    │   ├── 4a5b6c... (64-char hex) <-- Raw file content named by SHA-256
    │   └── e3b0c4...
    ├── snapshots/                  <-- Snapshot / commit records (empty)
    └── staging/                    <-- Staging metadata
        └── index                   <-- Staging index (path=hash pairs)
```

### Component Inventory

| Path | Purpose / Content | Created By | Read By | Modified By | Format | Role in Version Control |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `.vrn` | Root repository metadata directory | `InitCommand` | `AddCommand` | `InitCommand` | Directory (DOS hidden) | Contains all versioning state and object database. |
| `.vrn/config` | Configuration properties | `InitCommand` | Nobody currently | Nobody currently | Key-value text (`version=1`) | Will store repository format version and user settings. |
| `.vrn/HEAD` | Active snapshot pointer | `InitCommand` | Nobody currently | Nobody currently | Plain text (initialized to `NONE`) | Tracks current checked-out snapshot ID or branch reference. |
| `.vrn/objects/` | Content-addressed blob store | `InitCommand` | Nobody currently | `ObjectStore.store()` | Directory of raw files | Stores deduplicated file contents indexed by SHA-256. |
| `.vrn/snapshots/`| Commit / snapshot storage | `InitCommand` | Nobody currently | Nobody currently | Directory (currently empty) | Designed to hold snapshot tree manifests and metadata. |
| `.vrn/staging/` | Staging container directory | `InitCommand`, `StagingArea` | Nobody | Nobody | Directory | Isolates the staging index from other metadata. |
| `.vrn/staging/index` | Active staging index | `StagingArea.stage()` | Nobody currently | `StagingArea.stage()` | Plain text lines: `<rel_path>=<hash>\n` | Represents staged state prepared for the next snapshot. |
| `.vrnignore` | User exclusion pattern rules | `InitCommand` | `FileScanner` (via `VrnIgnore`) | User | Plain text (lines of rules/comments) | Excludes files/directories from tracking and scanning. |

---

## 7. File Scanning Engine

The file discovery engine is implemented in [FileScanner.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileScanner.java).

### Traversal and Path Processing Mechanics
1. **Project Root Detection**: Computed in [AddCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java#L29) via `Path.of(".").toAbsolutePath().normalize()`.
2. **Directory Traversal**: Traversal uses `Files.walk(projectRoot)`. This executes a recursive depth-first walk of the entire file tree.
3. **Directory Filtering**: Traversal does **not** prune directories during traversal. `Files.walk` traverses into all subdirectories (including `.git`, `.gradle`, `build`, and `.vrn/objects`). Non-files are excluded after discovery via `.filter(Files::isRegularFile)`.
4. **Path Relativization**: Discovered paths are relativized to the project root using `projectRoot::relativize(path)`.
5. **Ignore Application**: The project-relative path is checked against `ignore.isIgnored(path)`.
6. **Error Handling**: Exceptions thrown during hashing within the stream are rethrown as `RuntimeException(e)`, aborting the stream. The error is caught in [AddCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java#L64) with `Failed to add files: ...`.
7. **Symbolic Links / Reparse Points**: `Files.walk` is called without `FileVisitOption.FOLLOW_LINKS`. Therefore, symbolic links are not followed. On Windows, NTFS junctions and reparse points are treated as regular directories or skipped depending on JVM security context.

### Scanning Limitations
- **Full Tree Traversal**: Because `Files.walk` evaluates before `VrnIgnore` is consulted, `Files.walk` descends into ignored massive directory trees (e.g., `node_modules/`, `build/`, `.git/`, and `.vrn/objects/`). If `.vrn/objects` contains 50,000 blobs, `Files.walk` stats every single one before discarding them at the filter step.
- **Ignored Command Argument**: As established, passing `vrn add src/Main.java` still causes `FileScanner.scan(projectRoot)` to scan and hash the entire project.

---

## 8. `.vrnignore` Pattern Matching Engine

The ignore engine is implemented in [VrnIgnore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java).

### Pattern Matching Mechanics
1. Path separators are normalized to forward slashes: `path.toString().replace('\\', '/')`.
2. Rules are loaded via `Files.readAllLines(ignoreFile)`, trimmed, and filtered to ignore empty lines and lines starting with `#`.
3. Rule evaluation follows two paths:
   - **Directory Rules (`rule.endsWith("/")`)**:
     - Strips trailing `/` to get `cleanRule`.
     - Tests: `pathString.equals(cleanRule) || pathString.startsWith(cleanRule + "/")`.
   - **Non-Directory Rules (`matches(cleanRule, pathString)`)**:
     - If rule is `*`, returns `true`.
     - If rule contains `*`, it executes `rule.split("\\*", -1)`.
       - If `parts.length == 2`, tests `path.startsWith(parts[0]) && path.endsWith(parts[1])`.
     - Otherwise, tests: `path.equals(rule) || path.endsWith("/" + rule)`.

### Supported vs. Unsupported Syntax Matrix

```text
+------------------------------------+-----------+-------------------------------------------------------------+
| Feature / Syntax Pattern           | Supported | Code Behavior / Limitation                                  |
+------------------------------------+-----------+-------------------------------------------------------------+
| Blank lines                        | YES       | Filtered out via `!line.isEmpty()`                          |
| Comments (`# comment`)             | YES       | Filtered out via `!line.startsWith("#")`                    |
| Whitespace trimming                | YES       | Lines trimmed via `String::trim`                            |
| Trailing slash directory (`dir/`)  | PARTIAL   | Only matches if path starts with `dir/`. Does NOT match     |
|                                    |           | nested paths like `src/dir/file.txt`.                       |
| Single wildcard prefix (`*.log`)   | YES       | Splits into `["", ".log"]`; matches `path.endsWith(".log")` |
| Single wildcard suffix (`temp*`)   | YES       | Splits into `["temp", ""]`; matches `path.startsWith("temp")|
| Global wildcard (`*`)              | YES       | Explicit check: `rule.equals("*")` returns true             |
| Exact filename (`file.txt`)        | YES       | Matches `file.txt` or any `sub/path/file.txt` via endsWith  |
| Recursive glob (`**`)              | NO        | Splits into 3 empty parts; fails `parts.length == 2` check  |
| Pattern negation (`!pattern`)      | NO        | Treated as literal character `!`; no negation logic exists  |
| Multi-wildcard (`a*b*c`)           | NO        | `parts.length == 3`; falls through to literal string equals |
| Root-relative slash (`/build`)     | NO        | `path` is relative (`build`); does not match `/build`       |
| Missing `.vrnignore` file          | NO        | `Files.readAllLines` throws `NoSuchFileException`           |
+------------------------------------+-----------+-------------------------------------------------------------+
```

### Critical Edge Cases in `VrnIgnore`
1. **Crash on Missing `.vrnignore`**: If a user deletes `.vrnignore`, running `vrn add` immediately crashes because `new VrnIgnore(ignoreFile)` does not check `Files.exists(ignoreFile)`.
2. **Directory Rule Matching Flaw**: A rule `target/` will match `target/classes/App.class` because `pathString.startsWith("target/")` is true. However, it will **not** match `submodule/target/classes/App.class` because `pathString` starts with `"submodule/"`, and the rule does not check `pathString.contains("/target/")`.

---

## 9. Cryptographic Hashing (SHA-256)

Hashing is implemented in [FileHasher.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileHasher.java).

### Algorithm & Characteristics
- **Algorithm**: Standard cryptographic SHA-256 via Java's `java.security.MessageDigest`.
- **Digest Output**: 32 bytes formatted into a 64-character lowercase hexadecimal string using `String.format("%02x", b)`.
- **Determinism**: The digest is strictly deterministic; identical byte sequences always yield the exact same SHA-256 string regardless of file path, timestamps, or host operating system.
- **Collision Resistance**: SHA-256 provides a 256-bit keyspace ($2^{256}$ possibilities), making accidental hash collisions virtually impossible.

### Path vs. Hash vs. Content Separation
```text
+-------------------+-------------------------------------------------------------+
| Concept           | Meaning in VRN                                              |
+-------------------+-------------------------------------------------------------+
| Content           | The raw binary byte array stored in the file.               |
| Hash (SHA-256)    | The immutable, cryptographic fingerprint of the content.    |
| Path              | The mutable, human-readable filesystem location.            |
+-------------------+-------------------------------------------------------------+
```

If two files in different directories (`src/util/A.java` and `src/common/B.java`) contain identical code, both produce the identical SHA-256 hash. The content is stored **only once** in `.vrn/objects/<hash>`, while both paths are mapped to that single hash in `.vrn/staging/index`.

### Memory Limitation
`Files.readAllBytes(file)` loads the **entire file into the JVM heap** at once. 
- Files exceeding 2 GB will throw `java.lang.OutOfMemoryError` or `java.lang.IllegalArgumentException`.
- Processing multiple large files causes severe GC pressure because buffers are not streamed using chunked `InputStream` reading.

---

## 10. Content-Addressed Object Storage

The object store is implemented in [ObjectStore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java).

### Storage Pipeline: File to Object
```text
Working File: src/App.java
       │
       ▼ (SHA-256 Hashing)
Hash: "a1b2c3d4...64chars"
       │
       ▼ (Resolve in Object Store)
Object Path: .vrn/objects/a1b2c3d4...64chars
       │
       ├─► Files.exists(objectPath) == true  ──► Return early (Deduplication)
       │
       └─► Files.exists(objectPath) == false ──► Write raw bytes directly to objectPath
```

### Storage Characteristics
1. **Filename Generation**: The object filename is the raw, un-truncated 64-character hexadecimal SHA-256 hash.
2. **Flat Directory Structure**: All objects are stored directly inside `.vrn/objects/`. Unlike Git (which shards object directories using the first 2 characters, e.g., `.git/objects/4a/5b6c...`), VRN maintains an entirely flat directory.
3. **Deduplication**: If an object file with the given hash already exists in `.vrn/objects/`, `store()` exits immediately without writing.
4. **Immutability**: Once an object file is written, it is never modified. Subsequent file revisions generate different hashes and therefore create new object files.
5. **Storage Limitations**:
   - **No Compression**: Files are stored uncompressed.
   - **Lack of Retrieval API**: `ObjectStore` has a `store()` method, but **no `get()`, `read()`, or `retrieve()` method**. The current object store is a write-only sink.
   - **Non-Atomic Writes**: Writing directly to `objectPath` without writing to a temporary file first risks corruption if the JVM is killed mid-write.

---

## 11. Staging Area & Index Mechanism

The staging mechanism is implemented in [StagingArea.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java).

### Index File Format
The staging file `.vrn/staging/index` is a plain text file containing newline-delimited key-value pairs formatted as:
```text
<project-relative-path>=<sha256-hash>
```

Example:
```text
build.gradle=82f6e91f68e0d4bc35c6e864ad127ce6c0977d2426315ee428ee134d19313cb8
src/main/java/com/vrn/VrnApplication.java=e83a42d99d799042b4b455b5505e839e240c9d924d5ba811f5d6fe624dc2ba08
```

### Staging Characteristics & Architectural Gaps
1. **Append-Only Write Strategy**: The staging area uses `StandardOpenOption.APPEND`. Every call to `stage()` appends a line to `.vrn/staging/index`.
2. **Duplicate Entry Accumulation**: If `vrn add .` is executed multiple times, or if a modified file is re-staged, duplicate lines for that path are appended to the end of the file. There is no in-memory `Map<Path, String>` consolidation or file rewriting.
3. **No Reading / Parsing Capability**: `StagingArea` has no `readIndex()`, `getStagedEntries()`, or `clear()` methods.
4. **Deleted Files Handling**: There is no mechanism to record deleted files. If a file is deleted from the workspace, running `vrn add .` simply does not scan it; the file is neither removed from `.vrn/staging/index` nor marked as a tombstone.
5. **Non-Atomic Operations**: In [AddCommand.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java#L46-L60), `stagingArea.stage()` is called in a loop for each scanned file. If 500 files are added, the filesystem opens, appends, and closes `.vrn/staging/index` 500 separate times.

---

## 12. CLI Interface & Command Layer

### 12.1 Implemented Commands

#### Command: `vrn init`
- **Purpose**: Initializes a new VRN repository in the current working directory.
- **Arguments**: None.
- **Options**: None (standard help options `-h`, `--help` are not enabled on this subcommand).
- **Execution Flow**:
  1. Validates that `.vrn` does not exist.
  2. Creates directory hierarchy: `.vrn/objects`, `.vrn/snapshots`, `.vrn/staging`.
  3. Creates and initializes `.vrn/HEAD` (`"NONE"`), `.vrn/config` (`"version=1"`), and `.vrnignore` (`".vrn/\n"`).
  4. Applies DOS hidden attribute to `.vrn` directory on Windows.
- **Classes Involved**: [InitCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/InitCommand.java).
- **Filesystem Effects**: Creates `.vrn/` directory tree and `.vrnignore`.
- **Output**: `"Initialized empty VRN repository."` or `"VRN repository already exists."`.
- **Error Behavior**: Prints error message to `System.err`: `"Failed to initialize VRN repository: <message>"`.
- **Limitations**: Overwrites existing `.vrnignore` if present; does not support custom initialization paths.

#### Command: `vrn add`
- **Purpose**: Scans files, stores contents into object storage, and records entries into the staging index.
- **Arguments**: `<path>` (Required positional parameter at index 0, e.g., `vrn add .`).
- **Options**: None.
- **Execution Flow**:
  1. Resolves normalized absolute project root.
  2. Verifies presence of `.vrn` directory.
  3. Scans workspace files via [FileScanner](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileScanner.java) (filtering via [VrnIgnore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java)).
  4. Stores file contents in [ObjectStore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java).
  5. Appends entries to [StagingArea](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java).
- **Classes Involved**: [AddCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java), [FileScanner](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileScanner.java), [VrnIgnore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java), [FileHasher](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileHasher.java), [ScannedFile](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ScannedFile.java), [ObjectStore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java), [StagingArea](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java).
- **Filesystem Effects**: Adds new blob files to `.vrn/objects/<hash>` and appends lines to `.vrn/staging/index`.
- **Output**: `"Files staged successfully."`.
- **Error Behavior**: Prints error message to `System.err`: `"Failed to add files: <message>"`.
- **Limitations**:
  - The `<path>` parameter is completely ignored; it always scans the entire workspace root.
  - Crashes if `.vrnignore` is missing.
  - Re-reads files from disk twice.

### 12.2 Planned / Conceptual Commands (Not Implemented)
- `vrn commit [-m <message>]`: Create a snapshot from the current staging index.
- `vrn status`: Display tracked, modified, staged, and untracked files.
- `vrn restore <snapshot-id>` / `vrn checkout`: Revert working directory to a snapshot state.
- `vrn log`: Display historical snapshot graph, messages, timestamps, and authors.
- `vrn diff`: Compare changes between working directory, staging area, or snapshots.
- `vrn watch` / `vrn auto`: Start background daemon to detect changes and trigger automatic snapshots.

---

## 13. Snapshot & Commit Architecture (Prerequisites & Gap Analysis)

```text
Existing Infrastructure:
[x] `.vrn/snapshots/` directory created by `init`.
[x] `.vrn/HEAD` created with `"NONE"`.
[x] `.vrn/objects/` storing raw content blobs.
[x] `.vrn/staging/index` storing path-to-hash mappings.

Missing Requirements for `vrn commit`:
[ ] Staging index parser to read and deduplicate `.vrn/staging/index`.
[ ] Tree / Manifest data structure representing the full repository state.
[ ] Snapshot metadata model (Commit ID, Message, Timestamp, Parent Commit ID, Author).
[ ] Commit object serialization into `.vrn/snapshots/<snapshot-id>`.
[ ] Atomic `HEAD` update logic pointing to the new snapshot ID.
[ ] Staging area reset/flush logic after successful commit.
```

### Component Requirements for Commit Execution
To support `vrn commit "Initial snapshot"`:
1. **Index Reader**: [StagingArea](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java) must implement a method `Map<String, String> loadIndex()` that reads `.vrn/staging/index` and resolves duplicate entries (taking the latest entry for each path).
2. **Snapshot Model**: A data model representing a commit record:
   ```json
   {
     "id": "e4d3c2b1...",
     "parent": "NONE",
     "timestamp": "2026-10-05T16:00:00Z",
     "message": "Initial project snapshot",
     "tree": {
       "build.gradle": "82f6e91f...",
       "src/main/java/com/vrn/VrnApplication.java": "e83a42d9..."
     }
   }
   ```
3. **HEAD Management**: Upon writing the snapshot object to `.vrn/snapshots/<snapshot-id>`, `.vrn/HEAD` must be atomically updated with the new snapshot ID.
4. **Index Reset**: Staging must be reset or truncated to prepare for subsequent changes.

---

## 14. Restoration & Checkout Architecture

### Conceptual Pipeline
```text
Snapshot ID (e.g. 7f8a9b...)
      │
      ▼
Read Snapshot Manifest (.vrn/snapshots/7f8a9b...)
      │
      ▼
Manifest Map: { "src/App.java" -> "a1b2c3...", "README.md" -> "d4e5f6..." }
      │
      ▼ (For each path -> hash)
Object Store Lookup: .vrn/objects/a1b2c3...
      │
      ▼ (Read blob bytes)
Filesystem Write: Write bytes to projectRoot.resolve("src/App.java")
```

### Obstacles in Current Codebase
1. **ObjectStore Has No Retrieval Method**: [ObjectStore.java](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java) provides only `store(Path, String)`. There is no `byte[] retrieve(String hash)` or `InputStream openStream(String hash)`.
2. **No Clean / Overwrite Protection**: If untracked or locally modified files exist in the working directory during restore, a naïve restore could overwrite uncommitted work.
3. **Directory Recreation**: Files in subdirectories require ensuring parent directories (`Files.createDirectories(target.getParent())`) exist before writing content.
4. **Tombstone / Deletion Handling**: If a file existed in a previous snapshot but was deleted in a later snapshot, restoring the later snapshot must delete the untracked file from the workspace.

---

## 15. System Evolution Roadmap

```text
+-----------------------------------------------------------------------------------------+
|                                    STAGE 5: AI EXTENSION                                |
|  - Semantic change understanding (LLM/embeddings)                                       |
|  - Automated, context-aware commit message generation                                   |
|  - Natural language history querying                                                    |
+-----------------------------------------------------------------------------------------+
                                             ^
                                             |
+-----------------------------------------------------------------------------------------+
|                               STAGE 4: RESEARCH & EVALUATION                             |
|  - Empirical benchmark suite (noise rate, snapshot frequency, storage growth)           |
|  - Baseline comparisons: Fixed timer vs. Save-triggered vs. Change-aware               |
|  - Replay test harness using historical open-source developer sessions                  |
+-----------------------------------------------------------------------------------------+
                                             ^
                                             |
+-----------------------------------------------------------------------------------------+
|                            STAGE 3: AUTOMATIC VERSIONING ENGINE                         |
|  - Real-time filesystem observer (Java NIO WatchService)                                |
|  - Debouncing and write-burst smoothing                                                 |
|  - Multi-signal change significance scoring (AST, LOC delta, test run status)          |
+-----------------------------------------------------------------------------------------+
                                             ^
                                             |
+-----------------------------------------------------------------------------------------+
|                       STAGE 2: IMMEDIATE ENGINEERING ROADMAP                            |
|  - `vrn commit` implementation (Snapshot manifest, HEAD updates, metadata)              |
|  - `vrn status`, `vrn log`, and `vrn restore`                                           |
|  - Object compression (zlib / gzip) and sharded object storage                          |
|  - Comprehensive automated unit and integration test suite                              |
+-----------------------------------------------------------------------------------------+
                                             ^
                                             |
+-----------------------------------------------------------------------------------------+
|                           STAGE 1: CURRENTLY IMPLEMENTED CORE                           |
|  - Picocli CLI application (`vrn init`, `vrn add`)                                      |
|  - SHA-256 cryptographic file hashing                                                   |
|  - Raw content-addressable storage (.vrn/objects/<hash>)                                |
|  - Append-only staging index (.vrn/staging/index)                                       |
|  - Basic `.vrnignore` pattern engine                                                    |
+-----------------------------------------------------------------------------------------+
```

---

## 16. Automatic Versioning Engine Specification

*(Planned/Conceptual Specification)*

### Real-Time Ingestion Pipeline
```text
Filesystem Event (WatchService)
             │
             ▼
Debounce & Burst Buffer (Wait for disk writes to settle, e.g., 2000ms idle)
             │
             ▼
Change Detection & Diffing (Compare working tree against HEAD snapshot)
             │
             ▼
Change Analysis Engine (Extract structural & syntactic metrics)
             │
             ▼
Significance Evaluator (Compute composite significance score S)
             │
             ├─► Score S < Threshold: Discard / continue monitoring
             │
             └─► Score S >= Threshold: Trigger Autonomous Snapshot!
```

### Significance Signals
1. **Temporal Signals**: Active typing duration, idle time since last modification burst.
2. **Quantitative Code Metrics**: Net lines added/deleted, percentage of codebase modified, total files touched.
3. **Syntactic & AST Signals**: Modification of method signatures, class additions, interface changes (vs. whitespace or comment adjustments).
4. **Build & Test Triggers**: Interception of IDE compilation events or test suite execution (e.g., successful JUnit run triggers an automatic checkpoint).
5. **Structural & Environmental Signals**: Changes to build manifests (`pom.xml`, `build.gradle`), configuration files, or database schemas.

---

## 17. Research Methodology & Evaluation Framework

### Research Question
> *How effectively can change-aware snapshot policies preserve meaningful development states while reducing unnecessary snapshots, storage consumption, and processing overhead compared with conventional or naïve automatic snapshot strategies?*

### Experimental Policy Comparison

```text
+-----------------------+-----------------------------------------------------------------+
| Strategy              | Policy Description                                              |
+-----------------------+-----------------------------------------------------------------+
| Baseline 1: Periodic  | Snapshots taken strictly every N minutes (e.g. 5m, 15m).        |
| Baseline 2: On-Save   | Snapshots taken on every filesystem save/write event.           |
| Baseline 3: Manual    | Developer manually runs commit (standard Git baseline).         |
| Proposed Strategy     | Change-aware threshold scoring based on multi-signal analysis.  |
+-----------------------+-----------------------------------------------------------------+
```

### Quantitative Metrics

| Metric | Measurement Unit | Research Goal |
| :--- | :--- | :--- |
| **Snapshot Frequency** | Snapshots per development hour | Low to moderate |
| **Storage Overhead** | Megabytes / Gigabytes per session | Minimize via deduplication & pruning |
| **Unnecessary Snapshot Rate** | % of snapshots with non-functional or trivial changes | Minimize |
| **Missed Meaningful States** | % of functional intermediate milestones lost | Minimize |
| **Analysis Latency** | Milliseconds spent computing significance | Must be sub-perceptual (< 50ms) |
| **Restore Speed** | Milliseconds to restore workspace to any snapshot | Rapid recovery |

---

## 18. Technology Stack Audit

```text
+-----------------------+----------------------------------+------------------------------------+
| Technology            | Declared Version                 | Active Usage Status                |
+-----------------------+----------------------------------+------------------------------------+
| Java Platform         | Java 26 (Oracle JDK 26.0.2)      | CURRENTLY USED                     |
| Gradle Build System   | Gradle 9.6.0                     | CURRENTLY USED                     |
| CLI Framework         | Picocli 4.7.7                    | CURRENTLY USED                     |
| File I/O              | Java NIO.2 (`java.nio.file`)     | CURRENTLY USED                     |
| Cryptography          | `java.security.MessageDigest`    | CURRENTLY USED                     |
| Testing Framework     | JUnit 6.0.0 BOM (Jupiter Platform)| DECLARED BUT UNUSED (0 test files) |
| Database              | None                             | PLANNED / NOT USED                 |
| Compression           | None (Uncompressed raw bytes)    | PLANNED / NOT USED                 |
| Logging Framework     | None (`System.out`/`System.err`) | CURRENTLY USED                     |
+-----------------------+----------------------------------+------------------------------------+
```

---

## 19. Testing & Verification Audit

### Test Suite Audit
- **Test Files Found**: `0`
- **Gradle Test Output**: `Task :test NO-SOURCE`

```text
Repository Test Status:
src/test/java/      --> EMPTY DIRECTORY
src/test/resources/ --> EMPTY DIRECTORY
Unit Tests          --> NONE
Integration Tests   --> NONE
Regression Tests    --> NONE
```

### Untested Critical Behaviors
1. `FileHasher`: Hashing known strings and validating against standard NIST SHA-256 test vectors.
2. `VrnIgnore`: Rule parsing, comments, directory trailing slashes, wildcard prefix/suffix matching, and nonexistent ignore files.
3. `ObjectStore`: Content deduplication, object file creation, and immutability.
4. `StagingArea`: Appending entries, handling special characters in filenames, and path normalization across Windows and Linux.
5. `InitCommand` & `AddCommand`: End-to-end repository lifecycle tests.

### Recommended Test Suite Implementation Plan
1. **Unit Tests**:
   - `FileHasherTest`: Validate empty file hash (`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`) and multi-megabyte determinism.
   - `VrnIgnoreTest`: Verify patterns (`*.log`, `temp*`, `build/`, `README.md`, `# comment`).
   - `ObjectStoreTest`: Verify that storing the same content twice writes only once.
2. **Integration Tests**:
   - `InitCommandTest`: Execute `vrn init` in a JUnit `@TempDir` and assert presence of all directories, `HEAD`, and `config`.
   - `AddCommandTest`: Stage files in a temporary workspace and verify `.vrn/staging/index` contents and blob files in `.vrn/objects`.

---

## 20. Software Engineering Quality Review

### 1. Separation of Concerns & Modularity
- **Strengths**: [FileHasher](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileHasher.java), [ObjectStore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ObjectStore.java), and [VrnIgnore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java) are small, focused classes with clear conceptual boundaries.
- **Weaknesses**: [AddCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/AddCommand.java) tightly couples scanning, hashing, object storage, and staging within a single procedural script in `run()`.

### 2. Coupling and Cohesion
- **Coupling**: High coupling between [FileScanner](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/FileScanner.java) and [VrnIgnore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java). `FileScanner` directly instantiates `new VrnIgnore(ignoreFile)` instead of receiving an ignore policy via dependency injection.
- **Cohesion**: [ScannedFile](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/ScannedFile.java) provides high cohesion as an immutable record connecting scanner output to storage consumers.

### 3. Error Handling & Resource Management
- **Catch-All Exceptions**: Every class throws raw `throws Exception`, and CLI runners catch `catch (Exception e)`. This masks distinct failure modes (e.g., disk full vs. access denied vs. corrupted hash).
- **Missing File Failure**: If `.vrnignore` does not exist, `VrnIgnore` crashes with `NoSuchFileException`.
- **Double File Reads**: Files are read into memory twice during `vrn add` (first by `FileHasher`, then by `ObjectStore`).

### 4. Cross-Platform Concerns & Path Handling
- **Windows Attribute API**: [InitCommand](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/cli/InitCommand.java) uses `DosFileAttributeView` to hide `.vrn`. While guarded with `if (attributes != null)`, POSIX file permissions are unhandled.
- **Path Separators**: Paths are normalized to `/` in [VrnIgnore](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/VrnIgnore.java#L22) and [StagingArea](file:///c:/Users/Oggy/IdeaProjects/VRN/src/main/java/com/vrn/core/StagingArea.java#L22), ensuring consistent cross-platform staging keys.

### 5. Atomicity & Data Integrity
- **Lack of Atomic Transactions**: Staging writes directly via `Files.writeString(..., APPEND)`. If interrupted mid-write, partial entries corrupt the staging index.
- **Object Corruption Risk**: Objects are written directly to `.vrn/objects/<hash>`. An interrupted write leaves a corrupted, partial object that passes future existence checks.

---

## 21. Critical Architectural & Design Risks

```text
+---------------------------+-------------------+---------------------------------------------------------+
| Risk Category             | Severity          | Technical Impact                                        |
+---------------------------+-------------------+---------------------------------------------------------+
| Staging Unbounded Append  | HIGH              | Staging index grows infinitely; duplicate lines distort |
|                           |                   | staged state; slow linear parsing.                      |
| Double Disk I/O           | MEDIUM            | Every staged file is read twice from disk into memory.  |
| Memory Exhaustion         | HIGH              | Files.readAllBytes() crashes JVM on large files (>2GB). |
| Missing .vrnignore Crash  | MEDIUM            | Add command crashes if .vrnignore is deleted by user.   |
| Flat Object Store Limits  | MEDIUM-HIGH       | Filesystems (e.g., ext4, NTFS) suffer performance       |
|                           |                   | degradation with >50k entries in a single directory.    |
| Non-Atomic Object Writes  | HIGH              | Interrupted writes produce truncated, unrecoverable     |
|                           |                   | corrupted blobs.                                        |
| Ignored CLI Parameter     | HIGH              | `vrn add <path>` always adds entire repository root.    |
| Uncompressed Storage      | MEDIUM-HIGH       | 1:1 disk footprint leads to rapid disk exhaustion.      |
| Windows File Locking      | MEDIUM            | Windows file-locking semantics block deletion/rename    |
|                           |                   | during active WatchService or open streams.             |
| Lack of Deletion Tracking | HIGH              | Deleted files cannot be recorded or staged.             |
+---------------------------+-------------------+---------------------------------------------------------+
```

---

## 22. Recommended Next Implementation Milestone

### Milestone: Implement `vrn commit`
To complete the foundational version-control loop (`init` $\rightarrow$ `add` $\rightarrow$ `commit`), the immediate next milestone must be the **Snapshot / Commit Engine**.

```text
Current State:      vrn init  ──►  vrn add .  ──►  [DEAD END: Staged files cannot be committed]
Target Milestone:   vrn init  ──►  vrn add .  ──►  vrn commit -m "msg"  ──►  Snapshot Formed!
```

### Roadmap to Completion
1. **Refactor `StagingArea`**: Add `Map<String, String> readIndex()` and `void clear()` methods.
2. **Implement `Snapshot` Record**: An immutable record holding metadata (`id`, `parent`, `timestamp`, `message`, `files`).
3. **Implement `SnapshotStore`**: Component to serialize snapshots to `.vrn/snapshots/<snapshot-id>`.
4. **Implement `HeadManager`**: Component to read and atomically update `.vrn/HEAD`.
5. **Implement `CommitCommand`**: Picocli subcommand registering `vrn commit -m <message>`.

---

## 23. Final System Status Reference Block

```text
CURRENTLY WORKING
-----------------
- CLI invocation and command routing via Picocli (`vrn`, `vrn init`, `vrn add <path>`).
- Repository container initialization (`.vrn/`, `.vrn/objects`, `.vrn/snapshots`, `.vrn/staging`, `HEAD`, `config`, `.vrnignore`).
- Windows-specific folder hiding for `.vrn`.
- Full-tree recursive filesystem traversal using Java NIO `Files.walk`.
- SHA-256 cryptographic content hashing generating 64-character lowercase hex strings.
- Content-addressed object storage writing uncompressed files to `.vrn/objects/<hash>` with existence-based deduplication.
- Append-only staging of relative paths and hashes to `.vrn/staging/index`.
- Basic ignore pattern matching for exact filenames, single wildcards, and trailing-slash directories.

NEXT
----
- Implement `CommitCommand` (`vrn commit -m <msg>`) to serialize snapshot manifests and advance `HEAD`.
- Add `readIndex()`, deduplication, and `clear()` methods to `StagingArea`.
- Fix `AddCommand` so that it respects the user-specified `<path>` argument rather than always scanning the entire repository.
- Fix `VrnIgnore` crash when `.vrnignore` does not exist on disk.
- Create automated unit and integration tests under `src/test/java` using the declared JUnit 6 platform.

PLANNED
-------
- `vrn status` command to display working tree vs. staging index differences.
- `vrn restore <snapshot-id>` / checkout engine to reconstruct workspace files from object blobs.
- `vrn log` command to display snapshot history graphs.
- Object compression using `zlib`/`deflate` to minimize storage bloat.
- Object sharding (e.g. `.vrn/objects/ab/cdef...`) to avoid flat directory filesystem bottlenecks.
- Atomic file write mechanisms (write-to-temp-then-move) to prevent metadata corruption.

FUTURE RESEARCH
---------------
- Real-time filesystem observer daemon utilizing Java NIO `WatchService`.
- Multi-signal change significance scoring engine (AST delta, LOC count, build/test triggers).
- Autonomous snapshot policy engine comparing change-aware strategies against periodic timer and on-save baselines.
- Storage efficiency and state preservation evaluation framework using empirical developer session replays.

CRITICAL OBSERVATIONS
---------------------
- AddCommand CLI Bug: The `<path>` parameter is defined in Picocli but entirely ignored in code; `AddCommand` hardcodes `scanner.scan(projectRoot)`, staging the entire workspace unconditionally.
- Double Disk Read: Files are read into memory in their entirety twice during staging (once in `FileHasher`, once in `ObjectStore`).
- Memory Risk: `Files.readAllBytes()` is used throughout the pipeline; files approaching or exceeding heap limits will crash the JVM.
- Zero Test Coverage: Despite JUnit dependencies in `build.gradle`, `src/test` is completely empty and no automated tests exist.
- Write-Only Object Store: `ObjectStore` has no method to read or retrieve stored objects, making repository restoration impossible without code changes.
- Append-Only Staging Index: Running `vrn add .` multiple times causes `.vrn/staging/index` to accumulate duplicate lines indefinitely without map consolidation.
```
