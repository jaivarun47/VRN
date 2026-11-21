# Snapshot inspection and restore foundations — Task 9B

Task 9C now adds an executor and explicit preview/recovery CLI on these unchanged
planning foundations. Sections below describe the 9B API contract and historical
verification; see [working-tree restoration](working-tree-restoration.md) for the
current executing interface, recovery state machine and remaining limitations.

## Public command

From an initialized project's root, using the executable JAR described in README:

```powershell
java -jar $vrnJar inspect HEAD
java -jar $vrnJar inspect <full-snapshot-id>
```

Exactly one selector is required. IDs are exact, case-sensitive, complete matches
in the current HEAD ancestry. New IDs are UUIDs without dashes, not content
digests; historical safe IDs need not be 32 characters. A shorter ID works only
if it is itself an entire reachable identifier. There is no abbreviation lookup.
The literal `HEAD` is reserved for the captured active snapshot, including when
an older manifest happens to have that identifier. `HEAD=NONE` has no selectable
snapshot. Unknown and on-disk orphan IDs are outside the reachable selection scope.

The complete ancestry must validate even when the selected snapshot is found
early. Missing ancestors, cycles, malformed metadata, invalid IDs/roots, unsafe
metadata boundaries, and manifest path conflicts fail selection. There is no
registry, timestamp-based selection, branch/tag syntax, parent expression, range,
or directory-name sorting. Existing version-1 and supported legacy codecs remain
authoritative and unchanged. Content references accepted by a historical codec
must still satisfy actual SHA-256 object validation before inspection succeeds.

Example output (illustrative ID/hash):

```text
snapshot <full-id>
Parent: <parent-id-or-NONE>
Date: 1970-01-01T00:00:00Z
Message: "Initial snapshot"
Objects: verified
Files:
  <64-lowercase-hex-SHA-256>  "src/Main.java"
```

File paths sort by Java's natural string order, preserve recorded capitalization
and Unicode, and are quoted rather than normalized for display. Empty manifests
list `(none)`. Dates use recorded epoch milliseconds as UTC ISO-8601 instants.
Messages/path text use log's shared reversible quoting and explicit UTF-8 output;
controls and format characters cannot spoof entries. The pre-existing Windows
native commit-argument encoding limitation described in the history document is
unchanged; authoritative Unicode metadata displays correctly.

Success returns 0. Repository, selection, history, object, and I/O failures return
1 with stderr diagnostics and no partial normal listing. Missing/extra arguments
or unsupported flags return 2. Subcommand help, force options, filtering, and
pagination are not implemented. Commands do not discover parent repositories.
Output-device errors can still interrupt delivery after validation.

## Architecture audited before implementation

The starting branch was clean `core-completion`, HEAD
`fd9e9c46c036b19b6ba8649cf0ca1869fac76197`, matching the live remote; Task 9A
was committed and pushed. Its full Java 21 baseline passed before code edits.

Existing reusable pieces:

- `SnapshotHistory`: one validated HEAD anchor, iterative visited-set ancestry,
  strict observational preflight, and pending-recovery refusal.
- `SnapshotStore` / `MetadataCodec` / `SnapshotManifestValidator`: immutable
  manifests, embedded/filename identity, codec/UTF-8/field checks, Windows path
  structure and reserved-root rules. No second parser was needed.
- `ObjectStore.verify`: protected storage, non-following regular-file reads,
  streaming SHA-256 verification with a 64 KiB buffer, no repair or result cache.
- `WorkingTreePaths`: actual spelling, case-equivalent ambiguity, resolved-root
  containment and component checks. Its old file lookup conflated missing files
  with type replacements; a component-observation method now distinguishes them.
- `StagingIndex`: typed PUT/DELETE validation and existing case-insensitive path
  relationships. Applying an index in memory validates intent without publishing.
- `ContentCapture.observe` / `FileHasher.hash`: guarded component observations
  and non-following bounded-memory hashing. The byte-returning compatibility
  helper is not used by inspection or planning.

The mutation guard creates a lock file and existing mutation commands can run
recovery. Neither is used by the new services. An audit written before changes,
the original executable, and before-change failing tests are retained with the
disposable test evidence.

## APIs and object integrity

`new SnapshotResolver(storage).resolve(selector)` returns an immutable selection:
active/target manifests, captured HEAD ID, observed staging operations and a
SHA-256 fingerprint of the raw index bytes (`ABSENT` when absent). The shared
history traversal retains at most two full file maps, not all historical maps.

`new SnapshotInspector(storage).inspect(selector)` returns an immutable Snapshot
with stable file ordering after verifying every distinct selected object. Errors
include the selected snapshot, affected path and object reference. Missing,
malformed, unreadable, wrong-type, digest-mismatched and observable reparse
objects fail; nothing is repaired or replaced. Other ancestors' objects are not
audited merely because they were traversed. Inspection does not scan working files.

Object/working-file memory does not grow with content bytes. Manifest, ancestry,
staging, action and observation metadata still grow with entry count. No object
contents are pre-copied into temporary or working files.

## Whole-snapshot planning API

```java
RestorePlan plan = new RestorePlanner(projectRoot).plan("HEAD");
// Or plan("<exact-reachable-id>"); inspect plan.actions() and plan.isSafe().
```

This is a Java API, not a public planning or executing `restore` command. A
standalone probe against the packaged application demonstrates the same API in
tests. No file-specific scope, force/discard policy, staging changes, HEAD movement,
history rewrite or restoration execution is implemented.

`RestorePlan` contains the canonical project root, immutable selection, actions,
expected path observations, and required whole-directory capitalization changes.
Actions are sorted for stable inspection; this is **not an execution order**.
File/type replacement prerequisites identify necessary removal actions. A future
executor must group directory spelling transitions, create/prune appropriate
directories and choose a recoverable ordering; these steps do not run in 9B.

| Action | Meaning |
|---|---|
| CREATE | Target file is absent; record missing parents and any blocking tracked file. |
| REPLACE | Recover target bytes and/or exact spelling, possibly after a structural transition. |
| REMOVE | Current HEAD owns a file absent from target. Inspect `requiresChange` before interpreting this as a filesystem operation. |
| KEEP | Working file already has the target bytes and exact spelling. |

Actions include desired/current path, owning HEAD path/hash, target hash,
`requiresChange`, safety, reasons and prerequisites. `SAFE` means no conflict was
observed under the conservative policy; `CONFLICT` preserves local intent;
`UNSUPPORTED` identifies unsafe/ambiguous observations or unsupported filesystem
semantics. `isSafe()` requires every action to be SAFE. It is not a durable
authorization to execute the plan later.

### Ownership and conflicts

- HEAD owns recorded files. A clean observed file matching HEAD's content **and
  exact spelling** can be replaced or removed. Modified or unexpectedly renamed
  tracked files conflict when a change is required. Already-target content can
  be KEEP even if it differs from HEAD, since it is not overwritten.
- A HEAD-only file already missing has a REMOVE action with
  `requiresChange=false`. If it has become a directory, that action does not
  authorize deleting the directory or any of its untracked contents.
- Target-only missing files can be created with unobstructed destinations or
  after explicit safe tracked-file removals. Existing untracked destinations
  conflict even when bytes match. Staged-only additions confer no HEAD ownership.
- Unrelated untracked/ignored files and empty directories are preserved.
  Ignore rules never hide obstructions. Only affected type/case transition
  subtrees are enumerated; ordinary creation inside a safe existing directory
  need not remove or own unrelated siblings.
- Related PUT/DELETE intent is always a conflict, including KEEP. Unrelated
  staging is retained in the selection without globally blocking the plan.
  The captured index must be semantically valid against HEAD.
- Directory-to-file replacements enumerate every descendant, including ignored
  files and empty directories. Only clean HEAD-owned file removals and derived
  HEAD-owned directories can make that replacement safe. Unknown descendants,
  new empty directories, modified files or related staging conflict.
- Case-only file changes retain both spellings. Whole-directory spelling changes
  examine all affected members; untracked or modified siblings and staging
  conflict. Observable links or ambiguous case-sensitive destinations are
  UNSUPPORTED. Historical mixed shared-parent spellings remain inspectable,
  but plans requiring incompatible physical directory spellings are UNSUPPORTED.

The intended tracked-file set is the validated target manifest. Remaining
untracked files do not become disposable merely because they are outside it.
Root `.vrn` and `.vrnauto` paths are excluded by the authoritative path validator;
there is no operation against those internals.

## Preconditions and observation limits

Each relevant component records requested/actual spelling, type, content hash
for regular files, available file-key string, size, modification/creation times,
blocking ancestor and any unsupported observation. A missing suffix remains
distinct from an existing blocking file or unsafe intermediate component.
Affected directories additionally record complete child-name listings; null
means unlisted, distinct from an empty listing. The canonical root is recorded too.

Working-file hashes use 64 KiB streaming and before/after component/attribute
checks to detect evidence of change. Timestamps/size alone never certify content.
This Java 21 Windows provider returned null file keys; those remain optional
and are not represented as guaranteed physical identity. Hard-link aliases are
not proven unique or authenticated.

Before returning a plan, the service checks HEAD, raw index fingerprint, selected
and active manifest values, and pending-recovery state again. Detected repository
changes cause retry/refusal, not a mixed-generation successful plan. These are
optimistic observations, not an OS filesystem snapshot: working bytes, directory
entries, metadata or objects can change afterward or race reads. Cached directory
assessments belong only to this plan; no cross-operation success cache exists.

Pending recovery is refused at traversal boundaries, after object verification
and at final planning checks. No recovery record is parsed for mutation or
retired. Intentionally choose an existing mutation command to run established
recovery before retrying; it retains its normal staging/commit effects.

Existing Windows boundary checks reject observed symbolic links, junctions,
reparse entries, traversal, reserved internals, invalid components, aliases and
file/descendant conflicts. Project aliases remain caller-selected anchors.
Unknown reparse providers and hostile ancestor/in-place swaps are not universally
detectable through Java basic attributes. Cross-platform behavior is unqualified.

## Task 9C contract still to implement

Execution must revalidate the repository anchor, HEAD/index/manifests, relevant
path types/spellings/child lists and working hashes immediately before mutation;
reverify and safely consume target objects; coordinate cooperating writers; and
define recoverable ordering, rollback/cleanup, failure diagnostics and durability
limits. It must preserve untracked/staged content and group case/type transitions
without treating a plan as permanently current. No overwrite/confirmation policy,
transaction executor, backup preparation or restore recovery journal exists yet.

## Verification

Baseline: 817 total, 804 passed, zero failed, 13 unavailable Windows symbolic-link
privilege cases. Three focused inspection CLI tests failed before implementation
because the command was absent, then passed.

Task 9B adds 98 focused cases: 33 resolver/inspection/integrity, 45 planning/path/
staleness cases, and 20 packaged CLI cases. All passed, as did all 54 Task 9A
cases. Coverage includes exact/HEAD/unreachable selection, malformed ancestry,
Unicode metadata, streaming object failures, ignored/untracked/staged conflicts,
all four type/case transitions, nested replacements, case-sensitive ambiguity,
junctions, before/after byte-tree checks, repository races and stale preconditions.

The 80 MiB constrained-heap scenario runs packaged inspection and a standalone
planning probe with `-Xmx64m`; both verify the large object and the planner hashes
the large working file. Streaming fingerprints prove unchanged bytes/tree with
no new lock file. No Desktop manual-test repositories are accessed.

Focused checks:

```powershell
.\gradlew.bat test --tests '*SnapshotInspectionTest' --tests '*RestorePlannerTest' --tests '*SnapshotInspectCliTest'
```

The final clean offline Java 21 build passed in 9m59s:

| Check | Result |
|---|---|
| Final suite | 915 total; 902 passed; 0 failed; 13 unavailable; 40 test classes. |
| New Task 9B tests | 98 passed, 0 failed, 0 unavailable. |
| Existing regressions | All applicable 817 Task 8A–8D/9A and earlier cases preserved; exactly the same 13 privilege-unavailable cases. |
| Bytecode/runtime | All 70 application classes major 65, minor 0; packaged checks used Java 21.0.12.1. |
| Independent replay | 33 invocations in fresh disposable repositories; all expected outcomes. |
| JAR SHA-256 | `82365D15BAD33CE44474CED083AEAA851FD42F7420272005EB68A8039D9188E3` |

The replay created three snapshots with initial/changed/added/deleted files, a
case-only rename and a file-to-directory replacement. It verified log, HEAD/older
inspection, safe reverse-transition planning, modified/staged/untracked conflicts,
and independent corrupt-object, broken-history and pending-recovery refusals.
Every inspection/planning call preserved complete byte/tree fingerprints; failed
commands/probes produced no normal stdout. The Java planning probe used the public
API against the executable JAR, without introducing a public restore command.

Evidence lives at `C:\Users\Oggy\AppData\Local\Temp\VRN_Task9B_fd9e9c4`:
the pre-implementation audit, baseline/before-change/focused/final XML reports,
original JAR, bytecode inventory, standalone probe/replay scripts and outputs,
and `final-summary.json`. The normal report is
`build/reports/tests/test/index.html`. One earlier full run was intentionally
interrupted to include the final defensive error-handling review fix; the table
above describes the subsequent complete clean run.

Final checks confirm the original branch/HEAD and unchanged Git index SHA-256
`E4D87DF7192498361A90DA6EBF54C8FEEB0CAA55D571689A1643B19FFB883DB5`.
`git diff --check` passes. All 9B changes remain unstaged and uncommitted.
No production object storage, capture, publication, recovery or metadata codec
format was changed. Task 9C has not been started.
