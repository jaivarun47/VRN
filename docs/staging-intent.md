# Staging intent — Task 8A

MT-003 tightens case-only PUT handling; see [case-only-path-policy.md](case-only-path-policy.md).
The verification checkpoint below records the historical Task 8A completion.

The manual index is a current selection, not a command log. A path has PUT(hash),
DELETE, or no operation. Absence inherits from HEAD; it is not a persisted record.
`StagedOperation` is a sealed Java type with separate `Put` and `Delete` records.
No object hash, empty hash, or UNSTAGE sentinel represents deletion.

## Commands

Run these arguments through the Java 21 executable JAR from the project root:

```text
add src/Main.java
stage-delete src/OldFeature.java
unstage src/Main.java
status
commit -m "Update Main and exclude OldFeature"
```

`add` selects exact bytes as PUT and replaces a prior DELETE. Task 8B publishes an
entire eligible selection through one atomic index replacement; a failed batch
preserves the post-recovery index, including selections it would have replaced.
See [atomic-batch-staging.md](atomic-batch-staging.md). Missing files are errors; neither `add` nor `add .`
automatically selects deletions.

`stage-delete` selects one repository-relative tracked regular-file destination.
It works when the file is present or missing and replaces an existing tracked PUT.
A repeated DELETE is an idempotent success without rewriting the index. For a
staged addition absent from HEAD, it cancels that PUT without writing DELETE.
An unknown deletion is an error.

`unstage` removes one PUT or DELETE. An unselected valid file path is a successful
no-op without index publication. It never restores the HEAD version. Empty indexes
remain valid and contain zero operations; they do not permit a commit.

Both commands preserve working files, objects, snapshots, manual HEAD and `.vrnauto/`.
Paths are single-file selections: directories, traversal, absolute paths, root
internal storage, unsupported links/reparse entries and ambiguous identities fail.
Existing case aliases require successful filesystem lookup and actual spelling
agreement. Missing selections use exact stored spelling rather than guessing case
identity. Hard-linked names remain independent destinations. Replacing a staged
DELETE with PUT requires the working filename to retain the recorded filename
case. Case-only filename transitions are rejected rather than silently keeping
the old spelling. DELETE and unstage still accept proven lookup aliases.

## Status and commit

| HEAD | Index | Working file | Result |
|---|---|---|---|
| A | no operation | absent | Unstaged deletion; next snapshot inherits A |
| A | PUT(B) | absent | Staged modification plus unstaged deletion |
| A | DELETE | absent | Staged deletion only |
| A | DELETE | present | Staged deletion plus retained file shown as untracked |
| A | DELETE then unstage | absent | Unstaged deletion; no selected removal |
| absent | PUT then stage-delete | present | Untracked; staged addition canceled |

Selected absence differs from any present working bytes, even if unchanged from
HEAD or ignored. A tracked DELETE remains visible regardless of ignore rules.
Status is read-only, includes immutable staged-deletion results, and preserves
Task 7B's coherent metadata capture and post-scan change detection.

Commit loads HEAD, removes DELETE paths, applies PUT hashes, validates the complete
manifest, and verifies only the objects referenced by that final candidate. Objects
excluded by DELETE need not be readable or valid for this candidate. This does not
repair damaged older history: old references, objects and manifests remain intact.
A fabricated DELETE absent from HEAD is rejected, not silently committed.

Existing explicit unchanged PUTs can still create identical-content snapshots;
this policy is retained and tested. Canceling/unstaging all selections leaves no
operations and cannot create a snapshot.

## Metadata compatibility

Valid original legacy indexes and `VRN-STAGING<TAB>1` indexes read as PUT operations
without rewriting. Ambiguous/malformed legacy data continues to fail explicitly.
PUT-only mutations, including an empty index, retain v1. A DELETE requires v2:

```text
VRN-STAGING<TAB>2
files<TAB>2
put<TAB>base64-strict-UTF8-path<TAB>object-reference
delete<TAB>base64-strict-UTF8-path
```

`<TAB>` denotes a literal tab. Counts must exactly match records. Unknown versions
or operation names, incorrect fields, invalid/noncanonical Base64, malformed UTF-8,
duplicate/contradictory paths, case collisions and path conflicts are rejected.
PUT references retain the existing token syntax; actual SHA-256/object integrity
is enforced by ObjectStore and commit candidate verification.

Once the final DELETE is removed/replaced, subsequent writes can use v1 again.
Old binaries reject v2 rather than interpreting deletion as content. Use the new
binary while such intent is pending. Repository config and snapshot formats remain
version 1; historical metadata is never migrated or rewritten.

Core consumers use `StagingArea.readOperations()`. The existing `readAll()` PUT-only
compatibility API rejects DELETE explicitly rather than silently omitting it.

## Publication and recovery

Each new mutation uses the existing repository guard, preflight, protected metadata
paths, and atomic individual-index replacement. CommitRecovery retires any prior
cleanup barrier before publishing fresh staging. Recovery fingerprints the raw
index bytes, so it covers either index version without a recovery-format change.

HEAD publication remains the commit command's commit point; add's commit point
is its complete index replacement. Failed commit pre-publication retains intent;
published commits can return cleanup warnings. A consumed index is hidden from
status/typed reads even if cleanup failed. A changed fingerprint blocks cleanup;
later selections are preserved. Recovery housekeeping can legitimately complete
before a later selection error; there is no blanket rollback claim.

## Boundaries

Task 8A added no recursive deletion/unstaging, physical deletion, historical
deletion, restore, automatic snapshots, registry, batch-add atomicity or streaming
redesign. Task 8B now adds batch index atomicity; the other features remain absent.
File/directory replacement automation and full case-only rename commits remain
deferred. Index path-conflict rules remain in force. Locks coordinate cooperating
VRN processes; external namespace/content races and power-loss durability limits
remain as documented for prior tasks.

## Implementation files

| File | Change and responsibility |
|---|---|
| `src/main/java/com/vrn/core/StagedOperation.java` (new) | Sealed PUT/DELETE operation model. |
| `src/main/java/com/vrn/core/StagingPaths.java` (new) | Safe single-file selection using actual or exact missing spelling. |
| `src/main/java/com/vrn/core/StagingArea.java` | Typed reads, DELETE/unstage transitions, guarded atomic publication, PUT-only API compatibility. |
| `src/main/java/com/vrn/core/MetadataCodec.java` | Explicit staging-v2 records and deliberate v1/legacy compatibility. |
| `src/main/java/com/vrn/core/RepositoryLayout.java` | Validate either supported index representation. |
| `src/main/java/com/vrn/core/WorkingTreePaths.java` | Reuse identity checks with strict single-file selection for the new commands. |
| `src/main/java/com/vrn/core/CommitService.java` | Apply DELETE then PUT and validate/verify the final candidate. |
| `src/main/java/com/vrn/core/WorkingTreeStatus.java` | Staged deletions, retained working files and coherent typed metadata capture. |
| `src/main/java/com/vrn/cli/StageDeleteCommand.java` (new) | Single-file deletion-intent CLI and diagnostics. |
| `src/main/java/com/vrn/cli/UnstageCommand.java` (new) | Single-file removal of selected intent and no-op diagnostics. |
| `src/main/java/com/vrn/cli/StatusCommand.java` | Display staged deletions in the staged section. |
| `src/main/java/com/vrn/VrnApplication.java` | Register the two explicit commands. |
| `src/test/java/com/vrn/core/DeletionStagingRegressionTest.java` (new) | Initially failing packaged-CLI regressions and safety workflows. |
| `src/test/java/com/vrn/core/StagingIntentTest.java` (new) | Metadata, transitions, candidate verification, status and atomic index faults. |
| `src/test/java/com/vrn/core/StagingIntentRecoveryTest.java` (new) | Commit boundary faults, consumed intent, raw fingerprints and newer staging. |
| `src/test/java/com/vrn/core/WindowsStagingIntentTest.java` (new) | Case identity, sensitive destinations, hard links and reparse boundaries. |
| `src/test/java/com/vrn/MetadataFormatTest.java` | Unsupported-version probe advances from v2 to v3 because v2 is now supported. All baseline cases remain. |
| `README.md` | Six-command reference, examples, semantics and current limitations. |
| `docs/working-tree-status.md` | Extend the current contract while retaining historical Task 7B verification. |
| `docs/staging-intent.md` (new) | Task 8A behavior, format compatibility, recovery and limits. |

## Verification checkpoint

Work began on clean `core-completion` at `094a2cc`; Task 7C documentation was
already committed. Gradle 9.6.0 reran the baseline with the configured Temurin
21.0.12.1 compiler/test toolchain. Eight packaged-CLI regressions then failed
against the old implementation before any production changes.

| Run | Cases | Successful | Failed | Unavailable |
|---|---:|---:|---:|---:|
| Fresh baseline | 474 | 463 | 0 | 11 |
| Final clean Java 21 build | 567 | 555 | 0 | 12 |
| New Task 8A cases | 93 | 92 | 0 | 1 |

All original cases remain. The existing unsupported-index-version probe now uses
v3 because v2 is deliberately supported. All unavailable cases were assumption
aborts caused by missing Windows symbolic-link privileges, not successful tests.
The real junction, hard-link and case-sensitive-directory cases ran successfully.

Final verification used the existing offline caches, with the Gradle build JVM,
compiler and test executor on Temurin Java 21. All 41 production class files target
major version 65 without preview bytecode; the existing packaging checks passed.
No build settings, dependencies, global Java settings or existing user repositories
were changed.

The independent packaged Java 21 CLI smoke test passed 20 command checks in a new
external temporary repository. It exercised all six commands, a special-character
filename, deletion with a retained file, an empty next snapshot, preservation of
the previous manifest and stored object, unstaging after physical removal, staged
addition cancellation, and rejection of an empty commit.

The final source changes remain unstaged; branch, HEAD and Git's index are unchanged.
Task 8B was not started.
