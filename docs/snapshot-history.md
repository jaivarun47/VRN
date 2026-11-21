# Snapshot history — Task 9A

## Command and output

From the initialized project's root directory:

```powershell
java -jar $vrnJar log
```

There are no arguments or options. `--oneline`, `-n`, arbitrary identifiers, and
subcommand help flags are unsupported and return argument-error exit code 2.
Like the existing commands, log does not search ancestor directories. A nested
directory without its own repository is outside a repository for this command.

Entries follow HEAD's parent chain, newest descendant first, regardless of
identifier spelling, timestamps, directory enumeration, or file modification time:

```text
commit second
Parent: first
Date: 1970-01-01T00:00:01Z
Message: "Second snapshot"

commit first
Parent: NONE
Date: 1970-01-01T00:00:00Z
Message: "Initial snapshot"

```

These are illustrative identifiers and dates. Real output uses the full recorded
identifier, exact parent, and the manifest's nonnegative epoch-millisecond
timestamp formatted as an ISO-8601 UTC instant. No filesystem date is substituted.
Unlike Git's branch-oriented log, this is one active snapshot's ancestry; no
named branches, merges, refs, graph selection, or unreachable-snapshot listing exists.

Messages use a reversible quoted representation. Printable Unicode, punctuation,
and spaces remain intact. Quotes and backslashes are escaped; newline, carriage
return, tab, backspace, and form feed use their familiar backslash escapes. Other
control/format characters and Unicode line separators use `\uXXXX` escapes
(surrogate pairs for supplementary characters). Empty messages appear as `""`.
Message text cannot insert an unescaped entry header or terminal control sequence.
Output bytes are explicitly UTF-8; use a UTF-8-capable terminal or decoder.

A valid `HEAD=NONE` produces `No snapshots found.` and exit 0. Missing, malformed,
or inconsistent HEAD is an error, never empty history. Success is exit 0;
repository, ancestry, metadata, and I/O errors return 1 with a diagnostic on
stderr. The entire chain and display records are validated before normal stdout
is written. A later output-device failure can still leave partial delivered bytes;
this is not transactional terminal output.

## Architecture inspected before implementation

HEAD is one UTF-8 safe identifier or the exact sentinel `NONE`. A snapshot lives
at `.vrn/snapshots/<id>.snapshot`. Its immutable authoritative metadata records
the identifier, parent identifier, creation timestamp, message, and full file map.
The initial snapshot has parent `NONE`; `NONE` itself is not a valid snapshot ID.

The existing version-1 codec has a `VRN-SNAPSHOT` header, Base64-encoded strict
UTF-8 message/path fields, and explicit field/count checks. The supported legacy
representation remains readable without migration. SnapshotStore verifies the
filename/embedded ID and manifest path rules. No snapshot registry is implemented
or required. Unknown advisory files are neither consulted nor rewritten.

RepositoryLayout's preflight and full validation are observational. The latter
rereads HEAD and inspects recovery metadata, so it is unsuitable as the complete
single-anchor traversal operation. The writer guard creates a persistent
`mutation.lock` file and therefore is also unsuitable for strictly observational
history reading. Existing mutation commands explicitly run established recovery;
merely checking storage does not run it.

## Validation and read-only behavior

`SnapshotHistory` obtains one validated HEAD through the existing structural gate
(`inspectedHead`). It checks required layout/configuration, metadata boundaries,
and optional staging syntax/path consistency. It walks iteratively with a visited
set, loading each manifest through the existing codec and manifest validator.
`SnapshotStore.loadAnchored` retains those checks while avoiding another HEAD
read; public `SnapshotStore.load` keeps its existing preflight behavior.

Task 9B reuses this traversal for exact reachable selection, retaining only the
active and selected file maps in addition to display records. Ordinary `log`
still discards the file maps. Staging is observed with a raw-byte fingerprint for
planning revalidation; log's output and no-object-verification contract are
unchanged. See [snapshot inspection](snapshot-inspection.md).

Missing parents, repeated identifiers (including self-parenting), invalid IDs,
unsupported versions, malformed fields/encodings, incorrect embedded identity,
invalid paths, case aliases, and file/descendant conflicts all fail. A parent
failure identifies the referring child where safe. No ancestry is inferred,
repaired, silently truncated, or reconstructed by sorting snapshot filenames.

The reader retains visited IDs and lightweight display records, not every
snapshot's file map. Each manifest is still decoded and structurally validated
in full. Memory grows with history metadata plus the largest individual manifest
and final formatted output; it is not a constant-memory metadata parser.

No writer guard, recovery, metadata publication, object read/hash, registry
update, or working-tree scan is invoked. Log creates no repository lock or
temporary file. It can inspect a structurally valid historical manifest even
when its content object is missing; success certifies history structure, not
content integrity or restorability. Content-consuming operations must verify
the required objects independently.

### Pending recovery

Any existing `.vrn/staging/commit.pending` record, whether published, unpublished,
or malformed, causes refusal. Presence is checked before and after traversal.
The record and staging index remain untouched, including a consumed index.

Intentionally run an appropriate existing mutation command, such as `add .` or
`commit -m "message"`, to invoke established recovery before retrying log. These
commands retain their normal staging/commit effects: choose one deliberately.
There is no new recovery-only command, and malformed recovery can still require
investigation rather than automatic repair.

## Concurrent observation and remaining limits

A captured HEAD anchors one ancestry generation. Cooperating commits publish
immutable manifests before atomically replacing HEAD; a subsequent successful
commit need not appear in the current listing. A held writer lock alone does not
prevent a reader; an observed pending record does. No incompatible reader lock
protocol or weakening of commit publication has been introduced.

The start/end pending checks are observations, not a global filesystem snapshot.
A pending record can appear after the final check, and a complete intervening
commit/recovery may not be observed. That does not change the captured immutable
ancestry. External removal of a needed parent fails the read. External rewrites,
ancestor swaps, hard-link aliases, or hostile filesystem races are not prevented;
existing observable Windows metadata boundary checks still apply. Future
retention that removes reachable manifests would need reader coordination.
There is no additional power-loss durability guarantee.

Only HEAD-reachable history is inspected. Filtering, pagination, selection,
snapshot details, restore, diff, garbage collection, registries, and automatic
versioning remain outside this command.

## Verification

The clean pre-change Java 21 baseline was 763 cases: 750 passed, zero failed,
13 unavailable Windows symbolic-link privilege cases. Three initial packaged
CLI regression tests failed against the original JAR's unknown `log` command,
then passed after registration and implementation.

Task 9A adds 54 cases in `SnapshotHistoryTest` and `SnapshotLogCliTest`, covering
empty/single/multiple histories, authoritative ordering, Unicode/empty/multiline
messages, legacy metadata, invalid HEAD, malformed manifests, cycles, missing
parents, read-only byte/tree comparisons, pending recovery, and CLI argument errors.
Deterministic hooks exercise a cooperating commit, external parent removal,
and a pending record appearing during traversal. A Windows metadata-junction
case exercises the existing internal boundary rules.

Compatibility fixtures combine Task 8D's file/directory and case-only transitions
with existing unrelated PUT/DELETE selections and batch staging. Unicode messages
are committed through the normal Java core and inspected through the packaged
CLI. On this Windows Java 21 environment, non-ANSI characters passed as native
launcher arguments became `?` before the existing commit command stored them;
that separate commit-input limitation is not fixed by log. Direct authoritative
Unicode metadata and core-created Unicode commits display correctly in UTF-8.

A 1,500-entry ancestry is inspected with `-Xmx64m -Xss256k`. An 80 MiB file is
captured/committed under a 64 MiB heap, then removed along with its object in the
disposable fixture; log still succeeds without content access. These are bounded
test scenarios, not universal memory guarantees.

Run focused tests with the installed Java 21 toolchain:

```powershell
.\gradlew.bat test --tests '*SnapshotHistoryTest' --tests '*SnapshotLogCliTest'
```

The final clean offline Java 21 build passed in 15m13s:

| Check | Result |
|---|---|
| Baseline | 763 total; 750 passed; 0 failed; 13 unavailable. |
| Final complete suite | 817 total; 804 passed; 0 failed; 13 unavailable; 37 test classes. |
| New cases | 34 history-service + 20 packaged CLI, all passed. |
| Regression unavailability | Exact same 13 Windows symbolic-link privilege cases as baseline. |
| Packaged application | All 51 application classes: major 65, minor 0; replay used Java 21.0.12.1. |
| Standalone replay | 22 invocations in isolated Temp repositories, all expected outcomes. |
| JAR SHA-256 | `121B0D0774F944F9EB836CE3F0CA17368F7048CBFAFE134FFE33D69F03064651` |

The replay covers init/add/initial commit/modified commit, empty/single/multiple
logs, a Unicode core-created structural commit, a packaged case-only directory
commit, missing-parent failure, and pending-recovery refusal. Every log call
preserves the fixture's complete byte/tree fingerprint; both failure cases leave
stdout empty. No Desktop manual-testing repositories were accessed.

Evidence is retained at
`C:\Users\Oggy\AppData\Local\Temp\VRN_Task9A_e2dcd9d1`, including the
pre-implementation audit, baseline/before-change/focused/final XML reports,
bytecode inventory, standalone replay script/output, and `final-summary.json`.
The normal HTML report is `build/reports/tests/test/index.html`.

Final Git checks confirm `core-completion`, unchanged starting HEAD
`e4ef9797ecce60f0088b57f6917d0aaeba9dc1b6`, and an unchanged index SHA-256 of
`433BBBCCCC39A47F9DD907EBDD5F52262E6AD7AC148C73CC75F9264B15870654`.
Task 9A changes remain unstaged and uncommitted; `git diff --check` passes.
Task 9B has not been started. The next separately authorized task can build
full-ID snapshot inspection on this validated metadata path; future content
inspection/restoration must still verify the objects it consumes.
