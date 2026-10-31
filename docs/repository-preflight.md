# Repository preflight and metadata boundaries — Task 7A

## Validation contract

`RepositoryLayout.preflight` is the shared format/structure gate. It requires a
plain storage directory, plain `objects`, `snapshots` and `staging` directories,
regular readable `config` and `HEAD`, supported `version=1` configuration, a
single safe active identifier (or `NONE`), and the expected type/location of an
active snapshot and any existing index, pending record or mutation lock.
Optional entries may be absent; access errors are not treated as absence.

`RepositoryLayout.validate` adds parsing of active snapshot, index paths and
recovery metadata. It is read-only: no recovery, repair, migration, object hashing,
ancestry walk or historical snapshot enumeration. `SnapshotStore` remains a
metadata store, while `ObjectStore` verifies content and `CommitService` verifies
the final candidate's objects before activation. Parsing opaque legacy reference
tokens still does not certify their content integrity.

The preflight does not acquire a lock or call recovery. Snapshot loading uses the
nonrecursive structural gate, not full `validate`. Thus layout inspection can
load a snapshot and inspect recovery without cycling back into validation.

Public repository mutations acquire `RepositoryMutationGuard`, including object
publication, index stage/clear/recover, snapshot save and commit. They cannot
bypass the supported format and metadata boundary simply by avoiding the CLI.
Package-private `*Locked` operations require the caller's existing guard; they
also check paths at their individual access points. `AtomicFilePublisher` is a
low-level filesystem primitive used during initialization too, not a standalone
repository mutation service or activation API.

## Timing and command behavior

The guard performs structural preflight **before opening or creating the lock**,
then opens `mutation.lock` with `NOFOLLOW_LINKS`, acquires the existing fail-fast
OS lock, and rechecks structure under that lock. These checks do not reconcile
pending work. The lock channel is closed if validation after acquisition fails.

- Repeated `init` uses full read-only layout validation and preserves existing
  storage; invalid/incomplete repositories are rejected, not reinitialized.
  Fresh initialization retains exclusive storage creation and config-last
  completion; HEAD/config publication parents are checked. Repeated inspection
  does not create a new lock file or perform recovery.
- `add` performs full validation under a guard before scanning or object
  publication. Its subsequent existing per-file core operations each enforce
  structural preflight. This is still not an atomic multi-file add.
- `commit` gets preflight through its guard before recovery, candidate creation
  or publication. Candidate SHA verification remains before snapshot/pending/HEAD.
- `status` validates and reads staging plus HEAD/snapshot under one guard. The
  working-file scan follows after release. Its classification rules remain
  unchanged; this task does not fix false-clean deletions or ignored tracked files.

The guarded structural check does not parse every optional record. Operations
parse the metadata they consume; add/status perform full current-metadata
inspection. There is no implicit repository-wide integrity audit.

## Observable Windows boundary checks

`MetadataPaths` checks every controlled internal parent and relevant leaf using
`BasicFileAttributes` with `NOFOLLOW_LINKS`. It rejects symbolic links and
`isOther` entries before resolving them, as well as wrong file/directory types.
Windows junctions may satisfy `isDirectory(NOFOLLOW_LINKS)`; that predicate alone
is insufficient. Resolved plain directory parents must match the selected storage
anchor. Snapshot IDs are validated before constructing their relative filenames.

Configuration, HEAD, index, pending records and snapshots are read from
non-following channels with strict UTF-8 decoding. Recovery fingerprints use the
same protected read path. Each metadata publication/deletion checks the relevant
parents and existing destination again. Atomic publication retains unique
same-directory temporary files; production temporary writes do not follow a final
link or recreate a missing temporary file. There is still no ordinary-move fallback.

A caller-selected project directory and its ordinary ancestor aliases are the
anchor, rather than internal entries VRN owns. A project junction is resolved
for scanning, with actual containment established **before** lexical relative
path conversion. Internal storage junctions remain rejected. The existing
case-sensitive sibling containment test and filename/path policy are retained.
The implementation does not reject every ancestor feature, discover case flags,
or introduce new project-filename restrictions.

## Ignore configuration

`.vrnignore` is independent optional project configuration, not part of the
storage format. A genuinely absent file now means **no user ignore rules**.
Scanning can proceed and mandatory `.vrn`/`.vrnauto` protection still applies.
Repeated initialization and other commands do not recreate it. Existing rules
are never reset or overwritten. Fresh initialization still creates the initial
file when absent, as before.

An existing unreadable file, directory or dangling symbolic link is not silently
interpreted as an absent configuration. Scanning fails on those errors. Commands
that only inspect or publish storage do not need to consume project ignore rules.
No ignore-pattern semantics, tracked-file classification, or deletion behavior
are redesigned here.

## Failure, recovery and remaining limits

Invalid format, missing required structure and observed unsafe metadata paths
fail before initial lock creation, object/index publication or recovery cleanup.
Full semantic validation performed after acquisition can create the ordinary
empty persistent lock file before finding malformed metadata. A later failed
under-lock recheck can also leave that lock file. This is not an object, staged
change, snapshot or recovery update; the OS lock is released, not inferred from
the filename's presence.

Checks do not mutate or retire pending records. Normal operations can still
reconcile an older valid record before encountering a later error; no blanket
all-state-unchanged claim is made in that case. The commit protocol remains:

`verify candidate -> snapshot -> pending record -> HEAD -> consumed-index cleanup`

Successful HEAD replacement is the logical commit point. Confirmed publication
followed by cleanup failure stays success with a warning. Uninspectable rename
outcomes remain explicitly uncertain. Consumed-index fingerprints, newer staging
preservation and object non-overwrite behavior are retained. A stale HEAD pointing
to a missing snapshot now fails structural preflight earlier; unexpected but
structurally valid HEAD changes still fail the existing recovery reconciliation.

These are observable checks and non-following final-component opens, not a
race-free filesystem transaction. External processes can swap ancestors or
parents between checking, temporary-file creation, rename or deletion; cooperating
VRN locks do not stop them. Caller-selected ancestry is trusted as the anchor.
Unknown Windows reparse tags/cloud providers are not comprehensively detectable
or qualified through Java 21 basic attributes; no native tag scanner was added.
Regular-file hard links are not rejected or proven unique. External aliases can
change their bytes, and metadata checks are not authentication against tampering.

There is no added power-loss flush/durability guarantee, repair operation,
initialization resume, batch-add atomicity or cross-platform framework. Deletions,
status identity/classification, streaming capture and Java 21 build configuration
remain separate tasks (7B and 7C where applicable).

## Verification

Baseline: `develop`, `c20ac4b`, with the seven modified and five untracked Task 6B
files preserved. Java 21 baseline: 357 cases, 353 successful, zero failures,
four unavailable symbolic-link cases.

Before production changes, 31 of the 48 new preflight cases and 11 of the 17 new
Windows boundary cases failed; six new symbolic-link cases were unavailable.
The saved baseline bytecode also reproduced actual index writes through a fresh
staging junction. Current code rejects init/add/commit/status on that repository
with exit 1, leaving the external index unchanged.

Final Java 21 compilation covers all 44 main/test source files with `--release 21`.
The complete 18-class suite has 422 cases: **412 successful, zero failed, ten
assumption-aborted symbolic-link cases**, zero discovery-skipped. The ten are the
four original cases plus six new metadata-file symbolic links requiring an
unavailable Windows privilege. They are not counted as successful rejection tests.
All junction, supported project-alias, hard-link, case-sensitive containment,
serialization, object-integrity and recovery cases ran. No Gradle/JDK target or
dependency configuration was changed.

Existing fixture changes supply supported config/HEAD/directories rather than
relying on incomplete synthetic storage. The internal-change fixture now alters
a valid unknown config setting; the unexpected-HEAD recovery fixture supplies a
valid unexpected snapshot so recovery itself is still exercised. Two old tests
are updated for the explicit optional-ignore behavior, retaining internal-tree
protection and no-recreation assertions. No baseline test case was dropped.
