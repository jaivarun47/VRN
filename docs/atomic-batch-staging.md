# Atomic batch staging — Task 8B

Task 8C preserves this transaction contract and replaces batch byte retention with
[bounded-memory streaming capture](bounded-memory-capture.md). The implementation
inventory and verification checkpoint below describe the original Task 8B state.
Task 8D adds [complete case/type transitions](path-transitions.md) inside the same
transaction; the earlier MT-003 rejection boundary is superseded for supported adds.

One `vrn add <path>` invocation prepares all eligible files before replacing
`.vrn/staging/index` once. The replacement is the logical commit point. This
applies to single files, directories and `add .`; the command syntax is unchanged.

## Transaction and failure contract

`AddService` holds the existing non-reentrant `RepositoryMutationGuard` from
preflight and commit recovery through discovery, capture, object preparation,
candidate validation, publication and outcome inspection. Cooperating manual
commands cannot interleave with the batch. There is no second locking protocol.

Recovery runs before the transaction baseline is captured. A pending, published
commit may legitimately have consumed the old index. In that case the guarantee
starts with the **post-recovery** bytes (or index absence), not the original
on-disk consumed index. Recovery fingerprints and refusal of unexpected newer
staging bytes are unchanged.

The candidate begins with every existing PUT and DELETE. Selected files replace
only their matched operations with PUTs; unrelated selections survive. Paths,
case identity, operation encoding and the entire candidate path set are checked.
Every remaining PUT reference must be a valid SHA-256 identifier with a verified
object; remaining DELETEs must refer to HEAD paths. Serialization uses the
existing codec: PUT-only/empty indexes use v1, DELETE-bearing indexes use v2.
Legacy compatible indexes remain readable; no repository migration occurs.

| Boundary | Observed outcome |
|---|---|
| Discovery, capture, object preparation, validation or serialization fails | No candidate is published; baseline bytes/absence are preserved. |
| Index temporary write or atomic replacement fails and baseline is observed | Nonzero exit, explicitly confirms no partial index update. |
| Replacement reports an error but the complete candidate is observed | Success with a warning; the fully published index remains authoritative. |
| Lock release fails after the operation returned its confirmed result | Completed operation with a warning, rather than a rollback claim. |
| Index cannot be inspected or differs from both baseline and candidate | Explicit uncertain-outcome error; inspect state before retrying. No guessed rollback or corrective overwrite. |

Index inspection uses protected metadata paths and non-following reads while the
guard is still held. The atomic publisher requires an atomic move and has no
ordinary-move fallback. Preparatory errors never trigger deletion/reconstruction
of the authoritative index. No recovery journal for add is needed: before the
single index move, the old state remains authoritative; after it, the complete
new state is authoritative. An interrupted process may leave temporary files.

If a replacement throws and the candidate was already byte-identical to the
baseline, observing those same bytes cannot prove publication. Add conservatively
reports failure with unchanged bytes rather than claiming a confirmed replacement.

If recovery/preflight itself fails, add aborts before establishing a new batch
baseline. It does not claim that recovery left all original metadata unchanged.
Add never changes HEAD or snapshot manifests; recovery can retire preexisting
consumed-index housekeeping.

## Content capture and immutable objects

`FileScanner.scanForAddLocked` receives coherent HEAD/index membership and reuses
the existing traversal and Windows identity rules. Files are captured once;
their hash and stored bytes come from the same buffer. The source is checked
again before capture and opened with `NOFOLLOW_LINKS`. Capture/read/traversal
errors abort the whole batch. A file disappearing after selection but before
capture is an error, not a silently skipped member.

`ObjectStore.storeLocked` reuses the existing integrity/publication logic under
the caller's guard. A matching existing object is verified and reused by hash;
corrupt objects are rejected without repair. New objects can remain unreachable
if a later file or index publication fails. Those verified immutable objects do
not select any path until authoritative metadata references them. Rollback never
deletes them: an apparently unused object may still be needed elsewhere, and
reachability cleanup belongs to future garbage collection.

`StagingArea.putLocked` shares the previous single-file PUT/DELETE replacement
policy without writing metadata or reacquiring the guard. The existing public
single-selection staging API remains available; the CLI uses `AddService` for
whole-invocation atomicity.

## Preserved selection behavior

- Ignore rules filter untracked discovery. HEAD paths and staged-only additions
  remain eligible even when ignored, including directory selection (MT-001).
- Explicit ignored untracked files fail with an explanatory message and exit 1.
  An empty eligible directory reports `No eligible files` with exit 0 and no
  index publication (MT-002). Recovery may already have retired consumed state.
- `.vrn/` and `.vrnauto/` root internals are always protected independently of
  ignore rules. Nested resources and compatible Unicode/special filenames remain
  supported. Junction, reparse, containment and ambiguity checks remain enforced.
- Task 8D records supported actual spellings and necessary structural DELETEs
  atomically. Incomplete directory selections and unproven/ambiguous aliases fail
  explicitly; the resulting snapshot must pass the strict manifest validator.
- Missing working files never become implicit DELETEs. `stage-delete`, `unstage`,
  commit recovery, status categories and HEAD's commit publication point retain
  their existing semantics.

## Limits and Task 8C boundary

The mutation guard coordinates cooperating repository users; it does not lock
the working directory against editors or external tools. Selection and capture
occur over time. Files added after discovery can be missed, and captured files
may change or disappear afterward. The selected hashes describe captured bytes,
not a globally simultaneous filesystem snapshot. Rechecking paths and opening
the leaf without following links narrows races, but parent-directory replacement
and hostile in-place writes remain possible; this is not a descriptor-relative
filesystem security boundary.

At the Task 8B checkpoint, the scanner retained all selected files' whole-byte
buffers. Task 8C now uses metadata-only selection followed by sequential streaming
capture, retaining this guard lifetime, candidate-index construction and
single-publication contract. Metadata still grows with the selected file count.

Atomic visibility and logical crash consistency do not imply power-loss durability.
No file/directory fsync protocol or database-style transaction is claimed. There
is no restore, retention, object cleanup, automatic deletion detection, automatic
versioning, new CLI syntax or metadata format in this task.

## Changed components

| File | Responsibility |
|---|---|
| `src/main/java/com/vrn/core/AddService.java` (new) | Owns the whole add transaction, baseline, complete candidate, single replacement and truthful outcome inspection. |
| `src/main/java/com/vrn/cli/AddCommand.java` | Delegates one selection to the batch service; preserves CLI messages and displays confirmed-publication warnings. |
| `src/main/java/com/vrn/core/FileScanner.java` | Supplies locked discovery/capture using caller-provided known paths; adds a narrow hasher injection seam and pre-capture path check. |
| `src/main/java/com/vrn/core/FileHasher.java` | Opens captured files without following leaf links; retains whole-file capture and hash/byte pairing. |
| `src/main/java/com/vrn/core/ObjectStore.java` | Shares validated object storage with a package-private locked entry point, avoiding nested lock acquisition. |
| `src/main/java/com/vrn/core/StagingArea.java` | Shares PUT replacement in memory through a package-private locked merge helper. |
| `src/main/java/com/vrn/core/CommitService.java`, `ScannedFile.java` | Comments reflect batch coordination and the new byte-buffer consumer. No commit behavior change. |
| `src/test/java/com/vrn/core/AtomicBatchStagingTest.java` (new) | Deterministic transaction-boundary, locking, integrity and recovery regressions. |
| `src/test/java/com/vrn/core/AtomicBatchStagingCliTest.java` (new) | Actual executable workflows and object failures at every batch position with absent/v1/v2 indexes. |
| `README.md`, `docs/staging-intent.md`, `docs/add-ignore-investigation.md`, this file (new) | Current guarantees, preservation semantics, superseded historical caveats, limitations and verification evidence. |

## Verification checkpoint

The clean starting branch was `core-completion`, HEAD
`98044c5d372b7d7dffa1923e623f20a7ad0dcf18`. The locally recorded
`origin/core-completion` matched HEAD, including the committed MT-001/2/3 fixes.
`git ls-remote --heads origin core-completion` also confirmed that same remote HEAD.
Baseline: 610 tests, 598 successful, 0 failed, 12 unavailable Windows symlink
privilege cases. Temurin 21.0.12.1, Gradle 9.6.0, Windows 11; compilation uses
`--release 21` and tests execute on Java 21.

Before production changes, `AtomicBatchStagingCliTest` ran 13 cases against the
old JAR. Nine failed on full-index-byte preservation: object failures after 1,
2 or 3 prepared files, with absent, v1 and v2 indexes. First-object failures and
the successful workflow passed. These tests use real corrupt object fixtures
and the executable JAR, without timing or filesystem permission assumptions.

`AtomicBatchStagingTest` adds deterministic read, disappearance, discovery,
object publication, late candidate conflict, invalid existing PUT, index-write,
atomic-move, unsupported-move, cleanup and uncertain-outcome failures. It checks
single publication, continuous guard ownership, mixed PUT/DELETE preservation,
captured-byte integrity, retained objects, retry, unchanged HEAD/history and
pending recovery/fingerprint barriers. Packaged CLI tests cover init, add,
status, commit, ignored tracked/staged paths, empty/ignored selection, nested
special/Unicode names and reserved internals. Existing Windows case/reparse and
MT-001/2/3 regressions remain part of the full suite.

All repositories used by these tests are isolated JUnit temporary directories;
the user's existing Desktop test repositories are not accessed.

Final clean `--offline clean build` completed successfully on Java 21 in 7m14s:

| Check | Result |
|---|---|
| Full suite | 651 total; 639 successful; 0 failed; 12 unavailable; 30 test classes. |
| New batch regressions | 28 core + 13 packaged CLI cases, all successful. |
| Initial targeted pass | 40 successful; the additional identical-baseline regression subsequently passed in the full suite. |
| MT-001/002 | All 25 add/ignore regression cases successful. |
| MT-003 | All 18 case-only rename regression cases successful. |
| Unavailable cases | The same 12 assumption-aborted Windows symbolic-link privilege cases, not counted as successes. |
| Independent packaged replay | 13 Java 21 CLI commands in a fresh Temp repository: init, add, status, commit, real object failure, byte-identical failed index, preserved HEAD/history/DELETE, valid retained object, successful retry and clean final status. All 8 stored objects verified after fixture repair. |
| Packaged application bytecode | All 44 application classes are major 65, minor 0 (Java 21, no preview). |
| Executable JAR SHA-256 | `564A847F5870B1848BCE29891FB8644FC5955EEA260CE2082C956486A35DBD7A` |

Baseline, failing-regression, targeted and final XML reports plus the standalone
replay script and command logs were retained under
`%TEMP%/VRN_Task8B_2a70d8a9`. These are disposable local evidence, not repository
content or a runtime dependency. The normal HTML report is
`build/reports/tests/test/index.html`. Repeat the full verification with:

```powershell
.\gradlew.bat '-Dorg.gradle.java.home=C:\Users\Oggy\.jdks\temurin-21.0.12.1' --gradle-user-home 'C:\Users\Oggy\.gradle' --offline --no-daemon --console=plain clean build
```

HEAD, branch and the Git index fingerprint remained unchanged. Changes are
uncommitted on `core-completion`; no commit, push, branch switch or history
operation was performed. Task 8B is ready for review. Task 8C can proceed later
against this transaction boundary; no Task 8C or 8D implementation was started.
