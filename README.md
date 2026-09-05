
# VRN

## A Lightweight Change-Aware Automatic Versioning System for Software Projects

VRN is a lightweight version-control and automatic snapshot system designed for software development projects.

The project starts with a small, conventional version-control core inspired by the fundamental ideas behind systems such as Git: tracking project files, identifying file content using hashes, storing content efficiently, and recording project states as snapshots.

Once the core versioning system is established, VRN will be extended with change-aware automatic versioning. The long-term goal is to allow VRN to recognize meaningful development changes and automatically preserve important project states without requiring the developer to manually create a checkpoint every time.

---

# 1. Project Motivation

Traditional version-control systems rely heavily on explicit developer actions.

A developer typically decides when a project has reached a meaningful state and manually creates a commit:

```text
make changes
    ↓
test changes
    ↓
decide state is important
    ↓
commit
````

This works well when developers consistently create commits at appropriate points.

However, developers may sometimes:

* forget to commit before making risky changes
* make many changes between commits
* experiment with a feature and later want to return to an earlier state
* make a large number of changes without creating meaningful checkpoints
* create commits too frequently simply to avoid losing progress

VRN explores an alternative approach:

> Can a version-control system automatically preserve meaningful development states based on the nature and significance of project changes?

The project therefore combines two ideas:

1. **Conventional version control**
2. **Change-aware automatic snapshotting**

The conventional versioning layer provides the foundation on which automatic versioning can later operate.

---

# 2. Project Vision

The long-term VRN workflow is intended to look approximately like this:

```text
                    Project
                       │
                       ▼
                File Monitoring
                       │
                       ▼
                 Change Analysis
                       │
                       ▼
             Is the change meaningful?
                    /       \
                  No         Yes
                  │           │
                  ▼           ▼
               Ignore      Snapshot
                              │
                              ▼
                        Version History
```

The automatic versioning engine may eventually consider multiple signals when deciding whether to create a snapshot.

Possible signals include:

* amount of active development time
* lines of code changed
* percentage of the codebase changed
* number of files modified
* function-level changes
* structural changes
* cascading changes caused by dependencies
* weighted combinations of multiple change indicators
* future semantic or AI-assisted analysis

The initial implementation will use deterministic, measurable rules before introducing AI-based decision-making.

---

# 3. Research Scope

VRN is also intended as a research-oriented final-year B.Tech project.

The primary research direction is:

> How effectively can change-aware snapshot policies preserve meaningful development states while reducing unnecessary snapshots, storage consumption, and processing overhead compared with conventional or naïve automatic snapshot strategies?

The project therefore has two aspects:

### Engineering contribution

Build a functioning lightweight version-control and snapshot system.

### Research contribution

Evaluate different snapshot-generation strategies and determine whether a change-aware policy can preserve useful development states without generating excessive snapshots.

---

# 4. Initial Research Baselines

The automatic snapshot engine can eventually be evaluated against several baseline strategies.

Possible baselines include:

### Snapshot after every detected change

```text
change → snapshot
change → snapshot
change → snapshot
```

This provides strong state preservation but can create excessive snapshots.

### Fixed time interval

For example:

```text
every 5 minutes → snapshot
```

This is simple but does not necessarily correspond to meaningful development states.

### File-count threshold

Create a snapshot when a certain number of files have changed.

### LOC/change-percentage threshold

Create a snapshot when the amount of changed code exceeds a threshold.

### Weighted change score

VRN can eventually combine multiple signals:

```text
change score =
    time factor
  + file change factor
  + LOC factor
  + structural change factor
  + dependency impact
```

A snapshot is created when the resulting score exceeds a threshold.

This weighted approach is one possible foundation for the future change-aware engine.

---

# 5. Current Development Strategy

VRN is being developed incrementally.

The first stage intentionally implements a small conventional version-control system before attempting automatic versioning.

The development order is:

```text
Core Version Control
        ↓
Snapshot / Restore
        ↓
Change Detection
        ↓
Automatic Versioning
        ↓
Evaluation
        ↓
Optional Intelligent / AI Extensions
```

The reasoning behind this order is simple:

> The automatic versioning system should be built on top of a working version-control foundation.

This also makes the system easier to test and evaluate.

---

# 6. Current Technology Stack

VRN currently uses:

| Technology      | Purpose                             |
| --------------- | ----------------------------------- |
| Java 21         | Main implementation language        |
| Gradle          | Build system                        |
| Gradle Wrapper  | Reproducible project builds         |
| Picocli         | Command-line interface              |
| Java NIO        | Filesystem operations               |
| SHA-256         | Content identification              |
| JUnit 5         | Testing                             |
| SQLite          | Planned metadata storage            |
| WatchService    | Planned filesystem monitoring       |
| `java.util.zip` | Planned initial compression support |
| SLF4J + Logback | Planned logging                     |

The project currently targets **Java 21** even if a newer JDK is installed on the development machine.

The initial development environment is Windows, with Linux compatibility planned for later validation.

---

# 7. Current Architecture

VRN currently follows a layered structure:

```text
CLI Layer
│
├── InitCommand
└── AddCommand
        │
        ▼
Core Layer
│
├── FileScanner
├── VrnIgnore
├── FileHasher
├── ScannedFile
├── ObjectStore
└── StagingArea
```

The CLI commands are intended to remain relatively thin.

Their responsibility is to coordinate the core components rather than contain all version-control logic themselves.

---

# 8. Project Structure

The current project structure is approximately:

```text
VRN/
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
│
└── src/
    ├── main/
    │   └── java/
    │       └── com/
    │           └── vrn/
    │               ├── VrnApplication.java
    │               │
    │               ├── cli/
    │               │   ├── InitCommand.java
    │               │   └── AddCommand.java
    │               │
    │               └── core/
    │                   ├── FileScanner.java
    │                   ├── FileHasher.java
    │                   ├── VrnIgnore.java
    │                   ├── ScannedFile.java
    │                   ├── ObjectStore.java
    │                   └── StagingArea.java
    │
    └── test/
        └── java/
```

---

# 9. Repository Structure

When a project is initialized using:

```text
vrn init
```

VRN creates:

```text
project/
│
├── .vrn/
│   ├── objects/
│   ├── snapshots/
│   ├── staging/
│   ├── HEAD
│   └── config
│
└── .vrnignore
```

Each component has a distinct responsibility.

### `.vrn/`

Contains VRN's internal repository data.

It is hidden on Windows.

### `.vrn/objects/`

Stores actual file contents using their SHA-256 hash as the object identifier.

Conceptually:

```text
.vrn/objects/
├── 7f83b165...
├── a91c42e7...
└── 2cf24dba...
```

### `.vrn/staging/`

Contains temporary metadata describing the state prepared for the next snapshot.

The current staging model is:

```text
project-relative path → hash
```

For example:

```text
src/Main.java=7f83b165...
src/Utils.java=a91c42e7...
README.md=2cf24dba...
```

### `.vrn/snapshots/`

Reserved for permanent snapshot metadata.

This will be used by the future `commit` implementation.

### `HEAD`

Points to the currently active/latest snapshot.

At initialization:

```text
NONE
```

### `config`

Contains internal VRN repository configuration.

The current repository format is:

```text
version=1
```

This version refers to the **internal VRN repository format**, not the user's software project's version.

### `.vrnignore`

Specifies files and directories that VRN should not version.

The file is stored outside `.vrn` so that it can itself be included in project versioning.

---

# 10. `vrn init`

The first implemented command is:

```text
vrn init
```

Its purpose is to initialize a VRN repository in the current project directory.

It currently:

1. Creates `.vrn/`
2. Creates `.vrn/objects/`
3. Creates `.vrn/snapshots/`
4. Creates `.vrn/staging/`
5. Creates `HEAD`
6. Creates `config`
7. Creates `.vrnignore`
8. Initializes `HEAD` to `NONE`
9. Initializes the repository format to version `1`
10. Adds `.vrn/` to `.vrnignore`
11. Marks `.vrn/` as hidden on Windows

Current repository initialization therefore establishes the storage foundation required by later commands.

---

# 11. `.vrnignore`

VRN uses a project-level ignore file:

```text
.vrnignore
```

The initial syntax is intentionally simple.

Current planned V1 rules include:

* blank lines are ignored
* lines beginning with `#` are comments
* a trailing `/` represents a directory rule
* `/` is used as the path separator
* simple `*` wildcard matching is supported
* leading `/` can represent a project-root-relative rule

More advanced ignore features such as:

* negation using `!`
* recursive `**` patterns

are intentionally postponed.

Example:

```text
# VRN internal directory
.vrn/

# Build output
build/

# Temporary files
*.tmp
```

The filesystem itself determines whether something is actually a file or directory. The `/` at the end of an ignore rule only expresses the rule's intended matching behavior.

---

# 12. File Scanning

`FileScanner` is responsible for discovering project files.

The current scanner uses Java NIO:

```java
Files.walk(projectRoot)
```

It recursively traverses the project directory and selects regular files.

The scanner then converts discovered paths into **project-relative paths**.

For example:

```text
C:\Users\User\Project\src\Main.java
```

becomes:

```text
src/Main.java
```

This is important because VRN should not store machine-specific absolute paths.

The scanner also applies `.vrnignore` rules.

The conceptual flow is:

```text
Files.walk()
      ↓
regular files
      ↓
project-relative paths
      ↓
.vrnignore filtering
      ↓
files VRN should version
```

The filesystem stream is closed using try-with-resources to avoid resource leaks.

Future robustness work may consider:

* symbolic links
* Windows junctions/reparse points
* inaccessible directories
* very large project trees
* files disappearing during scanning

---

# 13. File Hashing

After ignored files have been removed, VRN hashes the remaining files.

The current algorithm is:

```text
SHA-256
```

The process is:

```text
file path
    ↓
read file bytes
    ↓
SHA-256
    ↓
256-bit hash
    ↓
64-character hexadecimal representation
```

The hash identifies the content.

For example:

```text
src/Main.java
      ↓
SHA-256
      ↓
7f83b165...
```

An important distinction:

> A hash is an identifier/fingerprint of content, not the content itself.

VRN stores the actual content separately in the object store.

---

# 14. Content-Addressed Object Storage

VRN currently uses a simple content-addressed object store.

The basic model is:

```text
hash → actual file content
```

For example:

```text
.vrn/objects/
└── 7f83b165...
```

The file named `7f83b165...` contains the corresponding original file bytes.

This provides basic content deduplication.

If two files contain identical content:

```text
src/A.java       → HASH_X
backup/A.java    → HASH_X
```

VRN only needs one stored object:

```text
.vrn/objects/HASH_X
```

The two paths can later reference the same object.

This gives the system the conceptual structure:

```text
path
  ↓
hash
  ↓
stored content
```

---

# 15. ScannedFile

`ScannedFile` represents the result of scanning and hashing a file.

It currently contains:

```java
public record ScannedFile(Path path, String hash) {
}
```

Conceptually:

```text
ScannedFile
├── path
│   └── src/Main.java
│
└── hash
    └── 7f83b165...
```

The path is project-relative, while the hash identifies the corresponding content.

This object allows the scanner/hash stage to pass both pieces of information to later components.

---

# 16. Staging

The staging area bridges the `add` operation and the future `commit` operation.

The current model is:

```text
project-relative path → content hash
```

For example:

```text
src/Main.java=HASH_A
src/Utils.java=HASH_B
README.md=HASH_C
```

This metadata does not contain the actual file content.

Instead:

```text
staging
    path → hash

objects
    hash → content
```

Together:

```text
src/Main.java
      ↓
   HASH_A
      ↓
.vrn/objects/HASH_A
      ↓
actual content
```

The staging state represents the files prepared for the next permanent snapshot.

---

# 17. `vrn add .`

The second implemented command is:

```text
vrn add .
```

The current command operates on the entire project root.

The complete flow is:

```text
vrn add .
     │
     ▼
 determine project root
     │
     ▼
 FileScanner
     │
     ├── recursively find files
     ├── make paths relative
     └── apply .vrnignore
     │
     ▼
 ScannedFile
     │
     ├── path
     └── hash
     │
     ├───────────────┐
     ▼               ▼
ObjectStore      StagingArea
     │               │
     ▼               ▼
actual content    path → hash
```

After the command, the repository contains both:

```text
.vrn/objects/
```

for actual content and:

```text
.vrn/staging/
```

for metadata describing the staged state.

---

# 18. Current CLI Commands

Currently implemented:

```text
vrn
vrn init
vrn add .
```

The initial CLI is built using Picocli.

The root command provides standard help/version handling.

Future commands will include:

```text
vrn status
vrn log
vrn commit
vrn restore
```

and eventually commands related to automatic monitoring/versioning.

---

# 19. Snapshot Model

The next major component is the snapshot/commit system.

A snapshot represents a complete project state at a particular point in time.

Conceptually:

```text
Snapshot
├── snapshot ID
├── parent snapshot
├── timestamp
├── commit message
└── files
    ├── path → hash
    ├── path → hash
    └── path → hash
```

For example:

```text
Snapshot: SNAPSHOT_123
Parent: SNAPSHOT_122
Message: Added login feature

src/Main.java       → HASH_A
src/Login.java      → HASH_B
README.md            → HASH_C
```

The actual file contents remain in the object store.

The snapshot primarily records the metadata needed to reconstruct the project state.

---

# 20. Planned `commit`

The planned command is:

```text
vrn commit "message"
```

The expected flow is:

```text
staging/index
      ↓
create snapshot metadata
      ↓
store snapshot in .vrn/snapshots/
      ↓
update HEAD
```

A commit should not need to hash and store all files again.

The staged path-to-hash state already identifies the required content objects.

The commit operation therefore converts temporary staged state into permanent project history.

---

# 21. Planned Restore

A future restore operation will use the snapshot metadata and object store:

```text
snapshot
    ↓
path → hash
    ↓
hash → content
    ↓
write content to path
```

For example:

```text
src/Main.java
      ↓
HASH_A
      ↓
.vrn/objects/HASH_A
      ↓
original file content
```

Because snapshots preserve project-relative paths, the project can be reconstructed independently of the original machine's absolute directory location.

---

# 22. Planned Status and History

Future commands include:

### Status

```text
vrn status
```

This will compare the current working tree against the staged/latest known state and report changes.

### Log

```text
vrn log
```

This will display snapshot history, including information such as:

* snapshot ID
* parent snapshot
* timestamp
* commit message

---

# 23. Automatic Versioning Engine

Once the conventional versioning core is complete, VRN will introduce automatic versioning.

The automatic versioning engine will monitor a project and determine when a meaningful snapshot should be created.

A simplified future workflow is:

```text
project changes
      ↓
change detection
      ↓
change analysis
      ↓
significance decision
      ↓
snapshot?
   /       \
 no         yes
 │           │
 ▼           ▼
continue   create snapshot
```

Potential change indicators include:

### Time-based signal

How long the developer has been actively working.

### LOC-based signal

How much source code has changed.

### Percentage-based signal

How large the change is relative to the project.

### File-based signal

How many files changed.

### Function-level signal

Whether individual functions or methods have changed.

### Dependency impact

Whether a change causes related components to change.

### Semantic analysis

Whether the change represents a meaningful conceptual modification rather than a trivial edit.

---

# 24. Monitoring

A future monitoring component will use Java's filesystem monitoring facilities, particularly:

```text
WatchService
```

The intended role is to detect filesystem changes without repeatedly scanning the entire project unnecessarily.

Monitoring and decision-making will remain separate concepts:

```text
WatchService
    ↓
detect change
    ↓
Change Analyzer
    ↓
determine significance
    ↓
snapshot engine
```

The monitoring system itself should not decide whether a change is meaningful.

---

# 25. Storage Management

Automatic versioning creates a new problem:

> Automatic snapshots can consume significant storage.

VRN will therefore eventually support configurable retention and storage policies.

Possible configuration includes:

* maximum number of automatic snapshots
* maximum storage allocation
* storage limits relative to project size
* retention rules
* different retention priorities for important project areas

An example policy could allow automatic snapshots to consume a configurable multiple of the current project size.

These values are design candidates and are not yet implemented.

---

# 26. Ignore and Generated Content

Automatic versioning should not continuously store generated or irrelevant project data.

Examples include:

```text
build/
target/
node_modules/
temporary files
generated artifacts
```

The `.vrnignore` mechanism provides the first layer for excluding such content.

The exact default rules and advanced ignore behavior will evolve as the system develops.

---

# 27. Future Advanced Project-Aware Versioning

A more advanced future direction is to allow developers to identify particularly important parts of a larger project.

For example:

```text
Large Project
│
├── frontend/
├── backend/
├── database/
└── critical-module/
```

A developer could potentially designate `critical-module/` as an important region.

VRN could then:

* preserve more versions for that region
* maintain separate records for important subdirectories
* reason about dependencies between parent and sub-project states
* preserve compatible combinations of parent and child states

This is a future research/design direction and is **not part of the current implementation**.

---

# 28. AI / Intelligent Decision Making

AI-assisted semantic analysis is considered a future extension rather than a requirement for the initial core system.

The initial system should first establish deterministic change-aware policies.

A later version could compare:

```text
Metric-based decisioning
          vs
Semantic / AI-based decisioning
```

Possible AI-assisted questions could include:

* Does this change represent a meaningful feature?
* Is this a structural refactor?
* Is this likely to be a stable development state?
* Does the change affect an important module?
* Is the current state worth preserving as a checkpoint?

AI should therefore be treated as an extension to the change-analysis layer rather than as the foundation of VRN.

---

# 29. Deduplication Strategy

The first storage strategy is file-level content-addressed deduplication.

Example:

```text
Snapshot 1

A
B
C
```

Later:

```text
Snapshot 2

A
B'
C
```

Only the changed content needs a new object:

```text
objects/
├── A
├── B
├── B'
└── C
```

The snapshots can reference the same unchanged objects.

This avoids copying complete project directories for every snapshot.

Future versions may investigate chunk-level storage and deduplication for larger files.

---

# 30. Design Principles

The current development follows several principles.

### Keep V1 small

VRN is intentionally not attempting to reproduce every feature of Git.

### Separate responsibilities

Each component should have a clear role.

```text
Scanner      → find files
Ignore       → filter files
Hasher       → identify content
ObjectStore  → store content
Staging      → prepare metadata
Snapshot     → preserve history
```

### Use project-relative paths

VRN should avoid machine-specific absolute paths in stored metadata.

### Content and metadata are separate

```text
objects/
    hash → content

metadata/
    path → hash
```

### Build the core before automation

Automatic versioning depends on a reliable snapshot mechanism.

### Measure before claiming improvement

The research component should evaluate snapshot policies experimentally rather than assuming that automatic versioning is better.

---

# 31. Current Implementation Status

| Component                       | Status  |
| ------------------------------- | ------- |
| Java/Gradle project setup       | Done    |
| Picocli CLI                     | Done    |
| `vrn init`                      | Done    |
| `.vrn` repository structure     | Done    |
| Windows hidden `.vrn` directory | Done    |
| `.vrnignore` creation           | Done    |
| `.vrnignore` parsing            | Done    |
| Recursive file scanning         | Done    |
| Project-relative paths          | Done    |
| SHA-256 hashing                 | Done    |
| Content-addressed objects       | Done    |
| Basic deduplication             | Done    |
| `ScannedFile` model             | Done    |
| Staging directory               | Done    |
| Basic staging metadata          | Done    |
| `vrn add .`                     | Done    |
| Selective file/directory `add`  | Not yet |
| Robust staging updates          | Pending |
| `vrn commit`                    | Next    |
| Snapshot metadata               | Planned |
| `HEAD` updates                  | Planned |
| `vrn status`                    | Planned |
| `vrn log`                       | Planned |
| `vrn restore`                   | Planned |
| Filesystem monitoring           | Planned |
| Change analysis                 | Planned |
| Automatic snapshots             | Planned |
| Retention/storage policies      | Planned |
| SQLite metadata                 | Planned |
| Compression                     | Planned |
| AI-assisted analysis            | Future  |
| GUI                             | Future  |

---

# 32. Development Roadmap

## Phase 1 — Core Repository

* [x] Project setup
* [x] CLI foundation
* [x] `vrn init`
* [x] Repository structure
* [x] `.vrnignore`

## Phase 2 — File and Object Management

* [x] Recursive scanner
* [x] Ignore filtering
* [x] SHA-256 hashing
* [x] Content-addressed storage
* [x] Basic deduplication
* [x] Staging metadata
* [x] `vrn add .`

## Phase 3 — Snapshots

* [ ] `vrn commit`
* [ ] Snapshot metadata
* [ ] Snapshot IDs
* [ ] Parent snapshots
* [ ] Commit messages
* [ ] Timestamp metadata
* [ ] `HEAD` management

## Phase 4 — Inspection and Recovery

* [ ] `vrn status`
* [ ] `vrn log`
* [ ] `vrn restore`
* [ ] Snapshot validation
* [ ] Recovery testing

## Phase 5 — Change Detection

* [ ] Filesystem monitoring
* [ ] Change detection
* [ ] Change statistics
* [ ] LOC analysis
* [ ] File-level significance
* [ ] Structural change detection

## Phase 6 — Automatic Versioning

* [ ] Snapshot decision engine
* [ ] Configurable thresholds
* [ ] Automatic snapshot creation
* [ ] Retention policies
* [ ] Storage limits
* [ ] Automatic snapshot evaluation

## Phase 7 — Research Evaluation

Evaluate VRN against baseline snapshot strategies using measurable criteria such as:

* snapshot frequency
* storage overhead
* snapshot creation time
* change-analysis time
* restore time
* meaningful-state preservation
* unnecessary snapshot rate
* missed meaningful-state rate

## Phase 8 — Advanced Extensions

* [ ] SQLite-backed metadata
* [ ] Compression
* [ ] Improved storage efficiency
* [ ] Advanced project/module tracking
* [ ] Semantic analysis
* [ ] AI-assisted snapshot decisions
* [ ] GUI

---

# 33. Development Philosophy

VRN is intentionally being developed from the fundamentals upward.

The core implementation is being understood and built manually before higher-level automation and agent-assisted development are introduced.

The goal is not simply to produce a working application.

The goal is to understand:

```text
file
 ↓
content
 ↓
hash
 ↓
object
 ↓
path → hash metadata
 ↓
staged state
 ↓
snapshot
 ↓
history
 ↓
restore
 ↓
automatic snapshot decision
```

Once this foundation is understood and functional, more advanced development can be safely layered on top.

---

# 34. Current Milestone

The current milestone is:

> **Establish a minimal conventional version-control core capable of identifying and storing project content and preparing it for snapshot creation.**

This milestone has now reached:

```text
vrn init
     ↓
repository created
     ↓
vrn add .
     ↓
files scanned
     ↓
ignored files removed
     ↓
content hashed
     ↓
objects stored
     ↓
path → hash staged
```

The next major milestone is:

```text
vrn commit
     ↓
permanent snapshot
     ↓
snapshot history
     ↓
restore
```

Only after this core workflow is reliable will the project move into automatic, change-aware versioning.

---

# 35. Name

The project is called **VRN**.

The name is a short form inspired by the concept of **versioning**.

It was chosen to be:

* short
* easy to type as a CLI command
* easy to pronounce
* independent of a single implementation feature
* suitable for both the core version-control system and future automatic versioning features

Example:

```text
vrn init
vrn add .
vrn commit "Initial snapshot"
```

---

# 36. Current Goal

The immediate development goal is to complete the fundamental version-control loop:

```text
init
  ↓
add
  ↓
commit
  ↓
log
  ↓
restore
```

After that, VRN can begin evolving from a conventional lightweight version-control system into a:

> **Change-aware automatic versioning and intelligent snapshot system for software projects.**

---

## Status

**Project:** VRN
**Current stage:** Core version-control implementation
**Current working commands:** `init`, `add .`
**Next major feature:** `commit`
**Primary language:** Java 21
**Interface:** CLI
**Target platform:** Windows initially, Linux later

```
```
