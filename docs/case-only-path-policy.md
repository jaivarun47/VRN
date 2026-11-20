# Case-only paths — MT-003

Full recorded case-only rename semantics remain deferred to Task 8D. This repair
makes unsupported filename transitions explicit without changing persisted formats,
rewriting history or treating content hashes as path-identity evidence.

## Root cause

Task 7B intentionally resolves supported Windows aliases to actual directory entries
before comparing HEAD, index and working content hashes. It prevents false
deleted/untracked pairs, but previously gave no separate indication of a terminal
filename spelling change when hashes matched. The old staging API could write
`PUT(HELLO.TXT, hash)` over HEAD's `hello.txt`. Commit inherited `hello.txt` and overlaid
the separate `HELLO.TXT` string key; manifest validation safely rejected the resulting
case-equivalent collision. Successful staging therefore did not imply a valid commit.

If the index instead still selected `PUT(hello.txt, hash)` and the working entry was
renamed after staging, the old commit could succeed with the recorded old spelling.
This was consistent with its content-only operation, but concealed the unsupported
working filename transition. MT-003 adds explicit status and PUT publication checks.

## Current policy

| Situation | Behavior |
|---|---|
| HEAD `hello.txt`, working entry `HELLO.TXT` | Status reports unsupported filename-case change, regardless of hash equality; it is not clean. |
| Add either spelling while that working filename differs | Exit 1 with a case-only transition diagnostic; preserve index and recorded history. |
| HEAD and actual entry both `hello.txt`, argument `HELLO.TXT` | Supported lookup alias; add selects `hello.txt`, not a rename. |
| Existing alternate-spelling PUT in a legacy/v1/v2 index | Preserve and inspect it; status reports staged spelling conflict; commit rejects before publication. |
| File renamed after PUT was staged | Affected PUT commit rejects while preserving staging and HEAD. |
| Staged-only new file changes filename case | Reject update until its previous selection is explicitly unstaged; a fresh PUT can then choose the new file name. |
| DELETE/unstage selected through an existing case alias | Continue using Task 8A's safe identity lookup; never rewrite or remove working files. |
| Unrelated PUT while another inherited file has an unsupported working name | May commit the unrelated selection; inherited spelling stays unchanged and status continues reporting the unrecorded filename change. |

Status remains a successful inspection (exit 0) when it can resolve the destination,
but emits `Unsupported case-only path changes:` and lists exact recorded/observed
spellings. Existing ambiguous, missing-alias and reparse failures remain explicit
errors. Content modifications still appear in their existing categories.

Restore the working filename's recorded case before adding/committing its content.
Use `unstage <path>` to remove a preexisting conflicting PUT. Neither diagnostic
performs filesystem renaming or discards staging automatically.

DELETE selects absence, not a rename: a retained uppercase file is untracked
relative to that selection, so a DELETE can commit without restoring its case.
Objects, old manifests and the actual file are preserved. Once a path is absent
from HEAD and staging, a new independent PUT can select a supported new spelling;
this does not introduce a single-snapshot rename operation.

## Parent-directory aliases and exact spelling

Established directory lookup behavior remains supported, including valid manifests
such as `Src/A.txt` alongside `src/B.txt`. These strings can identify separate file
destinations under a shared physical directory on the supported filesystem.

Add resolves each known destination component by component and proves its actual
identity before using its recorded spelling for the PUT. Actual bytes are still
read from the discovered file. When only parent-directory casing differs and the
terminal filename is unchanged, the known HEAD/index spelling is preserved. This
is content selection through a supported alias, not recorded directory renaming.
Physical directory-capitalization changes alone retain Task 7B's content-based
status behavior; recording directory spelling transitions remains deferred.

No lowercase metadata keys, hash-based rename detection, Unicode normalization or
file-key/hard-link identity merging is introduced. Genuine case-distinct ambiguous
destinations remain unsupported and fail through the existing component checks.

## Implementation and compatibility

- `CaseOnlyPaths` provides the shared small PUT boundary and diagnostics.
- `FileScanner.scanForAdd` rejects observed filename transitions before staging;
  existing parent-directory aliases keep exact known metadata spelling.
- `StagingArea.stage` enforces the boundary for direct core callers too.
- `CommitService` rechecks each selected PUT before candidate publication, including
  old index shapes and filename changes after staging. DELETE is unaffected.
- `WorkingTreeStatus` exposes immutable `caseOnlyChanges`; `StatusCommand` prints
  its diagnostic section without hiding content differences.

No staging, snapshot, config or recovery format changes are made. Existing indexes
remain readable without migration, and conflicting selections can be unstaged.
The mutation guard, preflight, atomic index publisher, object verification,
snapshot non-overwrite rules, recovery fingerprints and HEAD commit point remain.
Working files and snapshots are never renamed or rewritten by the correction.

Two Task 7B tests now construct their preexisting alternate-spelling index fixtures
directly because the corrected writer rejects creating them. Their old content,
identity, ambiguity and read-only assertions remain. The Task 8A alias test retains
DELETE/unstage checks and now verifies rejection of an unsupported PUT, then restores
the working filename case before selecting content again. No baseline cases are
removed; these changes explicitly reflect the tightened MT-003 policy.

`ManifestIntegrityIntegrationTest` also keeps its defensive checks against old
case-conflicting indexes by seeding that persisted shape after verifying the new
writer rejects it. Case-only failures now assert the specific IOException path;
file/directory-conflict assertions retain their previous exception type. Its corrupt
HEAD fixture stages valid content before making HEAD invalid, because PUT validation
must now inspect HEAD's manifest. Original index preservation, immutable-history,
no-publication, object and repository-validation assertions remain in place.

## Boundaries

No full case-only rename commits, general rename detection, directory replacement,
restore, history rewriting, atomic batch staging, streaming capture or automatic
versioning is added. Cooperating metadata reads are coordinated; external filesystem
races are still possible. PUT checks reject unreadable, ambiguous or unsupported
existing destinations; missing working files can still commit previously selected
bytes under their recorded paths. Unrelated unstaged changes are not frozen.

The existing uncommitted MT-001/MT-002 repair remains present. Its command file,
investigation report and two regression files are preserved; README receives only
the case-policy update and FileScanner is extended without removing ignore behavior.

## MT-003 file inventory

| File | Responsibility |
|---|---|
| `src/main/java/com/vrn/core/CaseOnlyPaths.java` (new) | Shared explicit PUT boundary and filename/spelling diagnostics. |
| `src/main/java/com/vrn/core/FileScanner.java` | Early filename rejection; proven parent aliases retain recorded PUT spelling; prior ignore repair preserved. |
| `src/main/java/com/vrn/core/StagingArea.java` | Reject conflicting direct-core PUT selections under the existing guard. |
| `src/main/java/com/vrn/core/CommitService.java` | Check selected PUTs before candidate publication, including preexisting indexes. |
| `src/main/java/com/vrn/core/WorkingTreeStatus.java` | Separate immutable case diagnostics from content comparisons and include them in cleanliness. |
| `src/main/java/com/vrn/cli/StatusCommand.java` | Display unsupported changes and corrective guidance. |
| `src/test/java/com/vrn/core/CaseOnlyRenameRegressionTest.java` (new) | Eighteen case-state, CLI, core, commit and compatibility cases. |
| `src/test/java/com/vrn/core/WindowsStatusIdentityTest.java` | Preserve legacy-index and content classification checks while asserting the tightened policy. |
| `src/test/java/com/vrn/core/WindowsStagingIntentTest.java` | Keep DELETE/unstage aliases; require restored filename case for replacement PUT. |
| `src/test/java/com/vrn/core/ManifestIntegrityIntegrationTest.java` | Keep defensive old-state/collision tests with explicit earlier rejection and corrupt-HEAD fixture ordering. |
| `README.md` | User-visible support boundary and guidance; prior MT ignore documentation retained. |
| `docs/working-tree-status.md` | Current case-status contract alongside historical Task 7B verification. |
| `docs/staging-intent.md` | Tightened PUT replacement policy alongside historical Task 8A verification. |
| `docs/case-only-path-policy.md` (new) | Investigation, decisions, compatibility, inventory and verification. |

## Verification checkpoint

Started on `core-completion` at `d2e1800`, with six existing uncommitted MT-001/MT-002
files preserved. A fresh Java 21 baseline passed before MT-003 production changes.
Thirteen of the initial fifteen new regressions failed against the old implementation;
the valid lookup-alias and explicit deletion controls passed. The final MT-003 class
contains eighteen cases, including genuine case-sensitive collisions and unrelated
staging/commit behavior.

| Run | Cases | Successful | Failed | Unavailable |
|---|---:|---:|---:|---:|
| Fresh baseline | 592 | 580 | 0 | 12 |
| Final clean Java 21 build | 610 | 598 | 0 | 12 |
| New MT-003 cases | 18 | 18 | 0 | 0 |

The final build used Gradle 9.6.0 and installed Temurin Java 21.0.12.1 for the build
JVM, compiler and tests, using existing offline caches. All original test cases
remain, with the fixture/exception changes described above explicitly reflecting
earlier rejection. Both prior MT ignore regression suites passed unchanged: 25 cases.
The twelve unavailable cases are Windows symbolic-link privilege assumptions, not
successful tests. Case-sensitive-directory, junction and hard-link checks ran.

Independent replay exercised 31 packaged Java 21 command checks against saved
before/after JARs in four fresh temporary repositories. It reproduced the original
clean/add-success/commit-collision sequence, confirmed explicit status/add rejection,
verified preserved preexisting index bytes and unstage, and showed that a post-staging
filename change now blocks the affected commit rather than publishing the old name.
Historical snapshot bytes and working contents remained preserved.

Packaging checks passed; all 42 production class files target Java 21 (major 65)
without preview bytecode. Whitespace checks passed. Branch, HEAD and Git's index
are unchanged; all existing and new work remains unstaged and uncommitted. No user's
existing manual test repository was accessed. Tasks 8B/8C/8D were not implemented.
