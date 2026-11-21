# Safe working-tree restoration — Task 9C

## Interface and scope

From an initialized project's root:

```powershell
java -jar $vrnJar restore HEAD
java -jar $vrnJar restore $snapshotId
java -jar $vrnJar restore --plan $snapshotId
java -jar $vrnJar restore --recover
```

`$snapshotId` is an exact full identifier from `log`. The target must be reachable
in the completely validated ancestry of the current HEAD. `HEAD` selects its
captured active snapshot; `NONE` is not a snapshot. Root-only discovery, strict
metadata/path validation and UTF-8 escaped output follow log/inspect.

Preview uses the read-only 9B planner. It lists CREATE/REPLACE/REMOVE/KEEP actions,
SAFE/CONFLICT/UNSUPPORTED classifications, reasons and structural prerequisites.
It creates no lock, journal, backup or temporary file. It returns 1 when required
changes conflict, and prints no successful plan for invalid history/objects.

Execution requires a safe whole-snapshot plan. There is no file-specific scope,
force, overwrite, hard/reset, discard-changes, branch or checkout interface.
`--recover` takes no selector and cannot be combined with `--plan`. Missing/extra
arguments and unsupported flags return 2. Operational errors/refusals return 1;
successful execution, no-op, preview and completed recovery return 0.

An already-matching tracked target can return no-op without adopting or changing
target-equal untracked files. This exception applies only when **no filesystem
change is necessary anywhere**; unsupported observations and related staging
still refuse. A target-equal untracked obstruction never authorizes a different
required mutation. The 9B planner's conservative classifications are unchanged.

Restoring B while HEAD identifies D changes working contents only. HEAD remains D,
staging remains byte-for-byte unchanged, and status compares restored contents
against D. A subsequent user add/commit creates a child of D. Missing unrelated
HEAD files still need explicit `stage-delete` before that commit; `add .` retains
its existing deletion-selection rules. Restore is neither history rewriting nor
a revert commit, reset or HEAD-moving checkout.

## Pre-implementation audit

The branch was clean `core-completion`, HEAD
`944dc918439c602556676fd23e584a7cdeaf271f`, matching the live remote. Task 9B
was committed and pushed. Before edits, the Java 21 suite passed: 915 total,
902 passed, zero failed, 13 privilege-unavailable Windows symbolic-link cases.

The design reuses RestorePlanner/Resolver/Inspector, MetadataCodec and manifest
validation, streaming ObjectStore, WorkingTreePaths/ContentCapture observations,
the established fail-fast mutation lock, and atomic metadata publication.
Planner hashes and exact spelling are useful preconditions, but observations
must be checked again after preparation and at individual mutations. File keys
may be null. Commit recovery is a HEAD/index cleanup protocol and cannot serve
as a working-tree restoration journal.

Isolated NTFS probes and the installed Java 21 WindowsFileCopy source exposed
a critical distinction: `ATOMIC_MOVE` unconditionally uses a replacing Windows
move, even without REPLACE_EXISTING. An empty directory replaced a regular-file
destination in the disposable probe. It is therefore never used for working
destinations. Eight non-replacing move collision probes preserved existing
files/empty/nonempty directories. The installed provider uses same-volume
`MoveFileEx(...,0)` for that path and retains file/security attributes.

## Coordination and conservative conflict protection

Execution first rejects an unsafe observation without creating a lock, then
acquires the existing repository writer guard and computes/verifies a fresh plan.
Only Windows NTFS execution is currently qualified. Source/destination volumes
are compared before renames/link publication; an observed cross-volume operation
is rejected, never intentionally delegated to a copy/delete fallback.

Modified tracked files, unexpected spelling changes, untracked or ignored
obstructions, related PUT/DELETE intent, new empty directories obstructing a
structural change, links/reparse entries and case ambiguity refuse execution.
Unrelated staging and working data remain intact. Read-only working originals
are refused rather than clearing their attributes or forcing deletion.

After preparation, every relevant planner observation is checked afresh: root
identity, ancestor types, actual spellings, complete affected directory listings,
current file hashes and available identity/attribute indicators. HEAD/index and
selected/active manifest fingerprints are checked. Each subsequent step checks
its specific precondition and result. Size/time alone never authorize a change.

## Preparation and mutation order

Independent original backup copies and every changed target file are prepared
inside protected `.vrn/restore-work/` using 64 KiB streaming and forced file
writes, followed by independent digest verification. Target reads use the existing
verified object stream. Preparation failure, including partial writes/insufficient
space, does not modify working contents. Storage requirements include original
backup copies, prepared target bytes and metadata; no assumed free-space promise
replaces handling real I/O failure.

The deterministic order is reconstructed from a validated resource catalog:

1. Detach changed original regular files into private storage using non-replacing
   same-volume renames. Retain their exact spelling, file identity and attributes.
2. Detach only authorized **empty** old directories, deepest first. No working
   directory is recursively deleted. Retained directories preserve their identity,
   ACL and attributes for reuse or rollback.
3. Attach prepared/reused empty directories in target spelling, shallowest first.
4. Publish complete prepared files with `createLink`, which fails if a destination
   exists. The content object itself is never linked into the working tree.
5. Verify all target file contents/spellings, obsolete tracked-file absence, linked
   publication identities and repository baseline; publish COMPLETE; clean assets.

This handles nested case-only changes and both file/directory replacement
directions without guessing alphabetical dependencies. Directory-to-file changes
first preserve descendants and move only proven-empty directories. Unexpected
children stop the operation. `Files.isSameFile` verifies hard-link publication
identity even on this provider where BasicFileAttributes.fileKey is null.

There is a visible gap between detaching an old name and publishing its complete
replacement. File data is never streamed over a working destination, but this is
**not a single atomic replacement of every file**, and never an atomic whole-tree
update. Hard links temporarily alias prepared bytes to published working files;
independent original backup copies remain separate.

Original entities are retained for rollback. Existing replacement-file Java ACL
lists/owner and hidden/system/archive flags are copied onto prepared replacements
when permitted; failure aborts preparation. Original file creation/modification
attributes and directory security identity survive rollback through the retained
entities. Directory modification times may change with membership operations.
Snapshots do not version permissions, ACL inheritance/audit metadata, or file
timestamps. New files/directories use preparation-location filesystem defaults;
this is not full Windows security-descriptor restoration.

## Separate write-ahead restoration journal

The authoritative record is `.vrn/restore.pending`; private resources are under
`.vrn/restore-work/` in fixed typed buckets. Both are protected automatically by
the existing mandatory `.vrn/` exclusion. Normal repository reads and mutations
detect either pending entry before locking, automatic commit recovery or scanning.
They direct users to `restore --recover` and do not perform recovery implicitly.

The strict `VRN-RESTORE` version-1 journal contains:

- Operation UUID, canonical project root and observed root identity.
- HEAD/target IDs; raw HEAD/index and active/selected manifest SHA-256 fingerprints.
- Original/target file and directory catalogs, exact Base64 UTF-8 paths, expected
  hashes and available creation/modification/size/key stamps.
- Recovery phase, progress cursor and an in-flight flag for uncertain operations.
- A checksum covering the complete canonical record.

Resource names are derived from validated numeric IDs, never arbitrary serialized
backup paths. Counts, IDs, types, phases, cursors, root binding, manifest ownership,
path rules, directory relationships and checksums are validated. The checksum
detects corruption; it does not authenticate metadata against a hostile writer
who can rewrite valid records and filesystem data.

Phases:

```text
PREPARING -> APPLYING -> COMPLETE -> cleanup
                 |
                 +-> ROLLING_BACK -> ROLLED_BACK -> cleanup
PREPARING -> ABORTED -> cleanup
```

Before every working mutation, the in-flight journal record is forced and
atomically published. After checking the filesystem result, progress advances.
An interruption is reconciled from exact pre/post evidence rather than an
in-memory counter. Lost filesystem/journal acknowledgements are treated as
uncertain until inspected. COMPLETE is the logical success point after target
verification; it never moves HEAD.

## Rollback, restart and cleanup

Ordinary execution errors attempt reverse-order rollback from the authoritative
disk journal. Originals are relinked without replacing any existing destination;
created publications are moved into private undo storage and checked before
disposal. Empty directories are moved back in reverse dependency order. Recovery
requires verified original backup copies and valid inventory before working
writes. Missing/corrupt backups, unknown resources, unexpected types, altered
baselines or unprovable state stop recovery with all remaining evidence retained.

An unexpected file is never blindly overwritten or deleted. A source changed
between revalidation and detach can be preserved in the private displaced area;
its unexpected bytes and independent original backup remain available. External
changes to published content similarly block rollback. These outcomes can leave
some working names absent until manual investigation; the diagnostic reports
incomplete recovery rather than successful rollback.

`restore --recover` takes the **same** writer lock through a narrow restore-barrier
bypass. It never runs commit recovery or alters staging. If commit and restore
records coexist, both protocols refuse to guess and preserve both records for
manual investigation. Resolve ordinary commit recovery before beginning restore;
cooperating commands cannot create this coexistence during an active restore.

Recovery prefers rollback for incomplete APPLYING/ROLLING_BACK operations and is
idempotent. PREPARING can be aborted without working changes. COMPLETE means only
cleanup is resumed, preserving any later working edits; it is never rolled back.
If cleanup fails after confirmed completion, exit 1 clearly reports completed
working restoration with cleanup pending. A ROLLED_BACK/ABORTED record similarly
permits cleanup to resume without reapplying working changes.

Cleanup validates all remaining catalog resources, removes individual known
files and empty directories only, removes the private work area, and deletes the
root journal **last**. Missing already-cleaned assets are tolerated only in
finished phases. Unknown entries or changed recovery data are preserved. An empty
unpublished bootstrap area can be explicitly retired. Missing journal with any
restoration data requires manual investigation, not guessed recovery. A real
process interruption can leave an unpublished atomic-metadata temporary file;
such orphan metadata is not used as an alternate journal or silently repaired.

When intervention is required, preserve the record and complete work area. The
catalog maps original file names to `backup/<id>` (independent verified bytes)
and `original-files/<id>` (retained actual entries); `undo/<id>` can contain moved
publications or unexpected bytes. Do not delete recovery resources or use a
normal command to bypass the barrier. Save external work and investigate the
reported path/identity mismatch before deciding how to recover manually.

## Durability and external-writer limitations

The guarantee is recoverability under tested process interruption, not guaranteed
power-loss durability. FileChannel.force(true) succeeded for ordinary files in
the local probe. Opening a directory channel for a directory force failed with
AccessDeniedException on this Java 21 Windows provider. Closed/forced files and
atomic metadata names do not prove durable directory entries or hardware flush
behavior after power loss. Journal/resource loss or filesystem damage can require
manual recovery. No database-style or whole-tree atomicity is claimed.

The writer lock coordinates cooperating VRN operations, not editors or hostile
processes. Fresh component checks, non-following file reads, exact hashes,
non-replacing destination operations and post-move verification reduce races.
Java's ordinary path APIs do not provide a race-free descriptor-relative boundary
against all ancestor swaps, mount changes, hard-link aliases, cloud/reparse
providers or in-place writes. Observable unsafe entries are rejected. These
remaining TOCTOU gaps and nonversioned attributes limit stronger isolation claims.

## Verification

Three initial CLI regressions failed before production changes and passed after
implementation. Focused coverage includes all four plan actions; initial/current/
middle history behavior; repeated restore; status and forward commit; Unicode,
spaces, `config=production.txt` and `#notes.txt`; modified/untracked/ignored/staged
conflicts; case/type transitions and nested changes; deterministic preparation,
journal, publication, rollback and cleanup faults; corrupted evidence; and
non-replacing destination races. Structural crash loops exercise every step in
four transition models, with fresh service instances and idempotent recovery.

Packaged child JVMs use hard `Runtime.halt(77)` at deterministic boundaries to
exercise actual process termination, pending detection and explicit CLI recovery.
The test-only crash probe is not included in the application JAR or exposed as
an end-user fault flag. Large-file testing uses historical 128/80 MiB files and
current 129/81 MiB files, restored under a 64 MiB Java heap through backup,
preparation, publication, verification and cleanup. Metadata fingerprints stream
instead of allocating complete large file byte arrays.

The final clean offline Java 21 build passed in **20m12s**:

| Check | Result |
|---|---|
| Baseline | 915 total; 902 passed; 0 failed; 13 unavailable. |
| Final suite | 1,039 total; 1,024 passed; 0 failed; 15 unavailable; 45 test classes. |
| New cases | 124 total; 122 passed; 0 failed; 2 privilege-unavailable symbolic-link cases. |
| New breakdown | 29 execution, 46 failure/recovery, 14 structural recovery, 17 journal/path safety, 18 packaged CLI. |
| Structural crash matrix | 19 individual mutation boundaries across four transition models, plus preparation and rollback boundaries. |
| Existing regressions | All applicable 915 cases preserved; the original 13 unavailability assumptions unchanged. |
| Runtime/bytecode | Java 21.0.12.1; all 86 application classes major 65, minor 0. |
| Independent replay | 39 Java invocations covering all eight requested scenarios; separate case-sensitive executor refusal also verified. |
| JAR SHA-256 | `8988C0028E22A758061577B48716DC746D5D28CF98C01FC303015F3091C5D076` |

The independent replay verifies initial and middle snapshot restoration, complete
historical trees, preview/refusal byte-tree invariants, case/type changes,
ordinary rollback, hard JVM termination at preparation/structural/completed
boundaries, explicit idempotent recovery, and preservation of an external edit
after COMPLETE. It restores a 128 MiB file under a 64 MiB heap and verifies a
subsequent forward commit's parent is the unchanged pre-restore HEAD. Historical
objects/manifests and staging remain byte-for-byte unchanged by restoration.

Evidence is retained at `C:\Users\Oggy\AppData\Local\Temp\VRN_Task9C_944dc91`:
the pre-implementation audit, baseline/before-change/focused/final XML reports,
original executable, NTFS move/force probes and installed-JDK source evidence,
bytecode inventory, isolated replay scripts/output and `final-summary.json`.
The normal HTML report is `build/reports/tests/test/index.html`. Two preliminary
extra case-probe fixture setup errors were corrected before the recorded
UNSUPPORTED refusal with unchanged byte/tree fingerprint; they did not alter
production code or user repositories.

Final checks confirm `core-completion`, unchanged Git HEAD
`944dc918439c602556676fd23e584a7cdeaf271f`, and unchanged Git index SHA-256
`25D2E68DF1775B633DB345BBDBC7C8C7669D20A38852D57576181190F0F60F29`.
`git diff --check` passes. No Desktop manual-testing repositories were accessed.
All changes remain unstaged/uncommitted. Series 9 is ready for review within the
documented Windows NTFS/process-recovery scope; the next phase has not started.
