# File/directory replacements and case transitions — Task 8D

`add <path>` supports proven case-only file and directory changes and selected
file/directory replacements. It preserves the complete-operation repository
guard, 64 KiB streamed object capture, immutable objects and one authoritative
staging-index replacement from Tasks 8B/8C. No new command, operation type, rename
record, object format or snapshot format is introduced.

Older binaries may reject pending compound transition indexes even though their
v2 encoding is unchanged. Use the current binary to commit or cancel that intent;
completed snapshots retain the existing readable snapshot format.

## Representation and validation

Transitions use existing operations. For example:

```text
HEAD:             hello.txt = A
Working:          HELLO.TXT = B
Index:            DELETE(hello.txt), PUT(HELLO.TXT, B)
Next snapshot:    HELLO.TXT = B
```

`StagingIndex.validate` validates each path and the PUT and DELETE destination
sets separately. A transitional overlap between those sets is meaningful; it is
not itself an invalid snapshot. `StagingIndex.apply` removes exact HEAD DELETE
keys, applies PUTs and validates the full result with the unchanged snapshot
path rules. Missing DELETE targets, surviving case-equivalent files and file/
descendant conflicts are rejected before publication. Newly selected directory
spellings in a transition with old-subtree DELETEs cannot retain inherited
descendants at the previous spelling. Ordinary legacy shared-parent aliases
without such transition intent remain supported.

Historical manifests retain their exact strings, including previously supported
mixed parent aliases such as `Src/A` and `src/B`. Reading does not normalize them.
A whole-directory add can reconcile their live paths to one actual spelling;
an incomplete descendant selection is rejected. Snapshot validation has only an
updated error message; its actual restrictions are unchanged.

## Identity and scope

Case folding finds candidate relationships. It does not prove a rename. Working
lookups must succeed, directory names must be unambiguous, and actual spelling
must match the selected source. Directory proofs check every component with
non-following attributes. Missing differently cased entries on case-sensitive
filesystems are not guessed into aliases. Two case-equivalent actual entries,
reparse paths, protected roots and unsupported Windows paths remain errors.

| Selected operation | Scope and resulting intent |
|---|---|
| Case-only file rename | Add either accepted lookup spelling, its directory or an ancestor. DELETE exact old HEAD spelling; PUT actual filename spelling. Changed and unchanged bytes both work. |
| Case-only directory rename | Select the highest changed directory component or an ancestor, including `.`. All affected known, present descendants are captured under their actual spellings. Descendant-only selection rejects with guidance. |
| File to directory | Add the replacement directory, an ancestor or an individual replacement descendant. DELETE the obstructing HEAD file; select eligible requested files. Untracked siblings outside the selection are not added. |
| Directory to file | Add the replacement file or an ancestor. DELETE all obstructing HEAD descendants; cancel staged-only descendant PUTs. Descendant paths cannot be selected through a working regular-file parent. |
| Empty eligible directory | No index publication and truthful no-op output, as before. Empty directories themselves are not versioned. |
| Unrelated missing tracked file | No implicit DELETE. It remains inherited until explicitly selected with `stage-delete`. |

Multiple directory/filename capitalization changes and combined case/type
replacements are supported when those proofs and scope rules hold. Prefix
comparisons require `/` component boundaries: `assets-more` is not inside `assets`.
No arbitrary rename inference, content similarity or persistent file-key identity
is used.

A directory case transition cannot silently delete an affected missing tracked
file merely to complete the spelling change. Restore it or explicitly stage its
DELETE first. An affected missing staged-only PUT must be restored or unstaged,
unless it is necessarily canceled by the selected type replacement. This makes
selection complete without introducing general missing-file deletion detection.

Ignore rules still exclude untracked discovery. HEAD/staged files remain eligible
even when ignored. Thus an ignored tracked descendant participates in a selected
directory case change, while an ignored new child of a replacement directory does
not become tracked just because the directory was selected.

## Reconcile existing staging

`PathTransitions.prepare` works on the in-memory copy of the current index under
the existing guard. For selected obstructions/aliases, it removes superseded PUTs
and generates DELETEs only for exact old HEAD paths. Staged-only obsolete paths
are canceled rather than receiving invalid DELETEs. Unrelated PUTs and DELETEs
remain intact.

Adding an unchanged spelling reverses an existing DELETE to PUT as before. Adding
a new case spelling retains the old-path DELETE and creates/updates its new PUT.
Repeated adds reconcile the existing pair rather than accumulating extra records.
Objects are captured and verified through the unchanged Task 8C implementation.

The complete candidate is semantically validated before its single index
replacement. A discovery, scope, capture, object, candidate or publication error
preserves the baseline raw index bytes when that state is confirmed. The baseline
is established after required commit recovery; recovery may legitimately retire
older consumed state first. Verified objects already prepared may remain
unreferenced, and are not deleted during rollback.

## Cancel a transition

Unstage remains a **single-record** command. There is no hidden transition group.
Exact index spelling is selected before lookup aliases; while two case-equivalent
records exist, an ambiguous third spelling requires the exact recorded side.

For a file case change:

```text
unstage HELLO.TXT     # Remove the replacement PUT first.
unstage hello.txt     # Then remove the old DELETE.
```

For `config` becoming `config/settings.json`, remove the child PUT(s), then the
`config` DELETE. For `assets/*` becoming the file `assets`, remove PUT `assets`,
then the individual old descendant DELETEs. For a directory case change, remove
the new descendant PUTs, then the old descendant DELETEs.

Each attempted cancellation validates the remaining applied manifest. Removing a
DELETE first is rejected if it would reinstate a case collision, file/descendant
conflict or mixed old/new directory spelling. Its serialized index remains
unchanged. Removing some replacement PUTs can leave explicit old DELETEs pending;
that is a valid deletion selection, not an automatic grouped rollback. To cancel
the entire transition, remove all its sides. Unrelated intent and working files
are never changed. A later add restores the complete selected transition.

Existing exact metadata records can be unstaged even if their working destination
is now an ordinary directory or lies below an ordinary replacement file. Unsafe
reparse and ambiguous lookups still reject. `stage-delete` remains a single-file
selection command; automatic structural reconciliation belongs to add.

## Status and commit

Before staging, observed full-path case changes (including directory components)
appear under `Case-only path changes not staged`, regardless of content hashes.
Content differences are still reported independently. Type replacements show
the absent old file(s) and eligible new working file(s).

After staging, DELETE old paths and PUT new paths retain separate logical
identities. Status reports staged deletions and additions, rather than folding a
valid pair into one content modification. Later working edits remain unstaged.
After commit, matching working contents and exact spellings are clean.

Commit applies the same semantic index rules before object verification and
snapshot publication. Its selected-PUT filename safety check remains: a further
case-only filename change after capture requires another add. Inherited unselected
paths are not renamed by commit. Incomplete legacy single-PUT case selections can
remain inspectable when identity is provable, but cannot publish a conflicting
manifest. The legacy single-PUT staging helper does not infer complete transitions;
callers requiring this behavior use AddService.

HEAD remains the commit publication point. Snapshot objects/history are never
rewritten or physically deleted. Existing raw-index recovery fingerprints include
all transition records. Layout inspection validates index structure without
reapplying a consumed old index to the new HEAD; recovery can therefore finish
cleanup and preserve subsequent fresh staging.

## Streaming, safety and limits

ContentCapture, ObjectStore publication and AtomicFilePublisher are unchanged.
Capture still uses a reusable 64 KiB buffer and verifies the prepared bytes before
publishing/reusing a SHA-256 object. New object publication requires the hard-link
semantics verified on Windows 11 NTFS; unsupported filesystems fail safely without
index mutation. No portability publisher redesign is included.

The guard coordinates cooperating repository mutations, not external editors.
Selection and source observations are not a filesystem snapshot. Attribute
checks cannot exclude same-size rewrites, timestamp manipulation or all namespace
races. No fsync/power-loss durability guarantee is added. Metadata/path storage
still grows with file count; unreachable objects await future cleanup.

Unsupported cases include arbitrary/non-case rename inference, missing-case
identity guesses on sensitive filesystems, ambiguous case-equivalent entries,
incomplete directory selections, and unsafe reparse or reserved paths. General
automatic deletion, recursive deletion/unstaging, multiple CLI path arguments,
restoration, history/log commands and automatic versioning remain out of scope.

## Changed components

| File(s) | Responsibility |
|---|---|
| `src/main/java/com/vrn/core/PathTransitions.java` (new) | Proves and reconciles only selected structural obstructions/case aliases, enforcing complete directory scope. |
| `src/main/java/com/vrn/core/StagingIndex.java` (new) | Typed index structure, shared HEAD application, strict resulting-manifest validation and directory completeness. |
| `src/main/java/com/vrn/core/AddService.java`, `FileScanner.java` | Actual-spelling selection, in-memory transition preparation, unchanged streamed capture and single index publication. |
| `src/main/java/com/vrn/core/StagingArea.java` | Operation-aware reading/publication; exact-record cancellation and invalid partial-unstage rejection. |
| `src/main/java/com/vrn/core/CommitService.java`, `RepositoryLayout.java` | Semantic commit integration and recovery-compatible structural index inspection. |
| `src/main/java/com/vrn/core/WorkingTreePaths.java` | Component-wise, non-following directory identity proof. |
| `src/main/java/com/vrn/core/WorkingTreeStatus.java`, `src/main/java/com/vrn/cli/StatusCommand.java` | Full-path case notices and separate staged transition sides. |
| `src/main/java/com/vrn/core/CaseOnlyPaths.java`, `SnapshotManifestValidator.java` | Updated safety diagnostics; snapshot path rules remain strict. |
| `src/test/java/com/vrn/core/PathTransitionTest.java`, `PathTransitionCliTest.java`, `PathTransitionSafetyTest.java` (new) | Transition/scope/cancellation matrix, deterministic failures, executable scenarios, constrained heap and recovery. |
| `src/test/java/com/vrn/core/AtomicBatchStagingTest.java`, `CaseOnlyRenameRegressionTest.java`, `ManifestIntegrityIntegrationTest.java`, `SnapshotManifestValidatorTest.java`, `StagingIntentTest.java`, `WindowsStatusIdentityTest.java` | Preserve atomicity, identity, encoding and rejection assertions while updating obsolete unsupported-transition expectations. |
| `README.md`, `docs/atomic-batch-staging.md`, `bounded-memory-capture.md`, `case-only-path-policy.md`, `repository-preflight.md`, `snapshot-path-model.md`, `staging-intent.md`, `working-tree-status.md`, this file (new) | Current behavior and clearly labeled historical checkpoints. |

## Initial verification checkpoint

Started on clean `core-completion`, HEAD
`bca0a92b0949dd1588eab841912bf71b176613b3`. Live `git ls-remote` confirmed the same
remote commit containing Tasks 8B/8C. Java 21 baseline: 685 total, 672 successful,
0 failed, 13 unavailable symlink-privilege cases. Temurin 21.0.12.1, Gradle 9.6.0,
Windows 11 NTFS; the wrapper launcher is Oracle Java 26.0.2 and the build/test JVM
is Java 21 with release-21 bytecode.

Before production edits, all eight new explicit/recursive cases failed: ordinary
file case changes rejected, directory case changes looked clean, and type changes
omitted necessary DELETEs. The first implementation passed all eight. An expanded
49-case transition matrix passed, including 28 deterministic fault points across
the four transition kinds. A 178-case existing/new targeted run had one obsolete
CLI test still expecting replacement rejection; that test now checks successful
replacement and unchanged history. All other cases passed (two privilege skips).

A subsequent 136-case run passed: 40 manifest integration, 75 snapshot validator,
10 packaged transition and 11 transition safety cases. Both file-to-directory and
directory-to-file CLI scenarios captured 128 MiB replacements with `-Xmx64m`,
independently verified their digests, committed correct manifests and preserved
old snapshots. Existing 8B/8C integrity, atomicity and source-capture suites remain
in the complete Java 21 build.

All fixtures and CLI scenarios use isolated temporary repositories. Existing
Desktop testing repositories are neither read nor modified. No commits, pushes,
branch changes or history operations are performed by this task.

The first full 761-case run exposed two additional obsolete index-overlap
expectations and one overbroad directory-completeness check affecting a supported
legacy metadata-only shared-parent merge. The expectations now accept complete
transitions while rejecting incomplete candidates and unsafe paths. Completeness
enforcement now requires selected old-subtree DELETE intent, preserving the
legacy shared-parent merge. Snapshot loading rules remain unchanged. The refined
transition/staging/Windows-compatibility target run passed before the final build.

The final pre-build target contained 138 successful cases: 57 transition,
62 staging-intent and 19 Windows path-policy cases. The transition matrix also
covers existing affected PUT/DELETE selections, staged-only file-to-directory
replacement and staged-only descendants during directory capitalization changes.

Standalone `ReplayTransitions.ps1` subsequently exercised all six requested
scenarios through the packaged Java 21 CLI, in fresh disposable repositories:

| Scenario | Observed result |
|---|---|
| `hello.txt` -> `HELLO.TXT` | Old DELETE + exact new PUT; committed manifest uses only `HELLO.TXT`. |
| `src/Main.java`, `src/Utils.java` -> `SRC/*` | Both old DELETEs and new PUTs; complete new directory spelling. |
| `config` -> `config/settings.json` | Obstructing file DELETE + child PUT; no file/descendant conflict. |
| `assets/*` -> `assets` | All obstructing tracked descendants deleted from the new manifest; replacement file captured. |
| Corrupt-object failure during directory case change | Nonzero exit and byte-identical prior index; HEAD/history preserved; fixture repair followed by a successful ordinary retry. |
| Large directory-to-file replacement | 134,217,728-byte source captured and committed using a 67,108,864-byte Java heap (`-Xmx64m`). |

All 61 CLI commands had their expected exits. Successful scenarios verified
before/staged/final status, exact decoded staging records and manifest spellings,
new HEAD, old snapshot bytes and every stored object's SHA-256. Each final status
was clean. Replay scripts, command logs, decoded indexes/manifests and summaries
are retained under `%TEMP%/VRN_Task8D_917f23bc`; they are disposable evidence, not
repository runtime dependencies.

## Final verification

The final clean offline Java 21 build passed in 10m26s:

| Check | Result |
|---|---|
| Baseline | 685 total; 672 successful; 0 failed; 13 unavailable. |
| Final complete suite | 763 total; 750 successful; 0 failed; 13 unavailable; 35 test classes. |
| New transition tests | 57 core + 10 packaged CLI + 11 safety/recovery = 78, all successful. |
| Deterministic fault matrix | 28 cases: discovery, capture, temporary write, object publication, candidate validation, index write and index move for all four transition kinds; raw index/HEAD/history preservation checked. |
| Task 8B | All 28 core and 13 packaged CLI batch cases successful. |
| Task 8C | Both original constrained-heap cases successful; streaming core 31 successful, 1 privilege-unavailable; source/object publication code unchanged. |
| MT-001/002/003 | All 25 ignore/add cases and all 18 updated case-only cases successful. |
| Unavailable cases | Same 13 Windows symbolic-link privilege assumptions as baseline; no new unavailability. |
| Bytecode | All 48 application classes major 65, minor 0; actual executable-test runtime Java 21.0.12.1. |
| Packaged JAR SHA-256 | `C5EDBC3FC71519097C800C9F6FF0982727D3BB15A4DDC0ABA45FF8B241DF2A39` |

Git checks confirmed `core-completion` and starting HEAD remained unchanged, with
no staged changes. The index SHA-256 remained
`25059D0C2B682720696A7A15409D285F3A4F7264393778C7B3FECC56BEF873BE`.
Task 8D changes remain uncommitted for review. ContentCapture, ObjectStore,
AtomicFilePublisher, FileHasher and CommitRecovery source files are unchanged
from the committed Task 8C baseline. `git diff --check` passed.

Baseline, before-change failing tests, intermediate and final XML reports, replay
scripts and logs are retained in the disposable evidence directory above. The
normal HTML report is `build/reports/tests/test/index.html`. Repeat full checks:

```powershell
.\gradlew.bat '-Dorg.gradle.java.home=C:\Users\Oggy\.jdks\temurin-21.0.12.1' --gradle-user-home 'C:\Users\Oggy\.gradle' --offline --no-daemon --console=plain clean build
```

The core is ready for a separately authorized Task 9A (`vrn log`). That read-only
feature should handle missing/cyclic ancestry defensively; no history traversal,
registry, log command or Task 9A implementation was added here.
