# Working-tree status and identity — Task 7B

## Content comparison contract

Status compares the captured HEAD manifest (H), effective pending index (I), and
working regular files (W). The index still selects additions/modifications only;
no deletion marker or new persisted operation is introduced.

For each supported destination:

1. Compare I with H to report a staged addition or staged modification.
2. Independently compare W with I when staged, otherwise with H. Report a working
   modification or deletion. Missing staged additions can therefore have both a
   staged addition and a working deletion.
3. Report W files absent from both H and I as untracked, subject to ignore rules.

`-` below means no entry/file, not a stored hash. A/B/C are distinct contents.

| H | I | W | Report |
|---|---|---|---|
| - | - | A | Untracked |
| - | A | A | Staged addition |
| - | A | B | Staged addition + working modification |
| - | A | - | Staged addition + working deletion |
| A | - | A | No content changes |
| A | - | B | Working modification |
| A | - | - | Working deletion |
| A | A | A | No content changes |
| A | A | - | Working deletion |
| A | B | B | Staged modification |
| A | B | C | Staged modification + working modification |
| A | B | - | Staged modification + working deletion |
| A | B | A | Staged modification + working modification (disk reverted to H) |

The existing five result lists and CLI sections are retained. `deleted` now
includes missing HEAD **or staged** regular files, independently of staged content.
A file replaced by a directory, or a former parent directory replaced by a file,
means the old regular-file destination is absent; new eligible files are displayed
separately. This does not make replacement commits supported.

## Tracked paths and ignore rules

Every H/I path is inspected directly through `WorkingTreePaths.trackedFile`,
regardless of user ignore rules. Eligible untracked discovery continues to use
`ProjectFileWalker` and the unchanged `VrnIgnore` grammar. Both sets are combined
by actual relative spelling for hashing, without duplicate reads for the same
resolved destination. Existing whole-file hashing memory behavior is unchanged.

An ignored parent can contain tracked files that must still be compared and new
untracked files that remain hidden. A missing optional `.vrnignore` still means
no user rules, as defined by Task 7A. Mandatory internal `.vrn`/`.vrnauto` pruning
and manifest rejection remain independent of user rules.

Inability to inspect a required tracked path is an error, not proof of deletion.
Only observed `NoSuchFileException` or a non-reparse type replacement establishes
absence of the old regular-file destination. Access/read/enumeration failures,
observed symbolic/reparse entries, and ambiguous or changing entry spellings fail
status explicitly. Ignoring a tracked junction does not make it safe to follow
or hide. CLI operational errors remain exit 1 with a reason and no ordinary stack
trace. Missing/malformed repository metadata still fails through Task 7A preflight.

## Stored spelling versus working destination

The existing path helper is extended, rather than replaced by a second persistent
identity model. For each tracked path component it:

- Checks the requested lookup using non-following basic attributes.
- Rejects observable symbolic/reparse entries before resolving internal components.
- Enumerates directory names and requires a unique supported case candidate.
- Uses actual directory-entry spelling and existing resolved-root containment.

A unique case-insensitive text candidate alone is not proof of identity. If the
filesystem rejects the original lookup in a sensitive directory, that stored
spelling remains missing. It is not silently redirected to a differently cased
existing file. Neither `Path.equals` nor `Files.isSameFile` proves identity here.
No global lowercase keys, Unicode normalization, physical-file-key deduplication,
case-flag discovery or persistent capability cache is introduced.

On ordinary case-insensitive directories, `Main.java`/`main.java` and supported
mixed directory spellings such as `Src/A.java` plus `src/B.java` can resolve to
their actual existing entries without false deleted/untracked pairs. H and I
spellings that both resolve to the same entry are compared as one destination.

Staged output retains index spelling. Working modifications and untracked output
use actual discovered spelling; missing-file output uses the selected stored
spelling (I when present, otherwise H). Metadata files are never normalized or
rewritten by status.

The conservative support boundary remains important:

- Multiple case-equivalent actual component names in a tracked lookup are an
  explicit unsupported ambiguity, including when a colliding untracked name is
  ignored. This conservatively rejects ambiguous directory components too.
- Distinct complete case-equivalent working destinations fail the existing
  manifest policy; status does not silently merge them.
- Different case-equivalent H/I spellings with no unambiguous common existing
  destination fail explicitly instead of being guessed into one selection.
- If only `upper.txt` exists in a sensitive directory and the requested
  `Upper.txt` lookup fails, H's `Upper.txt` is missing and `upper.txt` can be
  untracked. These are not proven aliases.
- Independent hard-link names remain independent destinations even when their
  contents change together. Distinct valid Unicode spellings remain distinct.

This is status interpretation of supported regular-file destinations, not full
case-distinct NTFS support or an exact emulation of all native Unicode case tables.
Case-only rename staging/commit semantics remain deferred. A content-clean status
does not promise that an independently staged spelling transition can be committed;
the unchanged commit manifest validator can still reject the merged spellings.
Stored object integrity is likewise not certified by a clean status.

## Metadata and scan consistency

Initial capture holds the existing repository guard while validating layout and
reading HEAD's identifier, its manifest and the effective pending index. No
recovery cleanup occurs. Immutable private copies are used during comparison.

Resolution, traversal and hashing run outside the lock. After scanning, status
reacquires the guard and captures the same semantic metadata again. A different
HEAD identifier, committed file map or effective index produces:

`Repository metadata changed during status; retry the operation.`

A lock held by another mutation can instead produce the existing busy error.
There is no broad lock across scanning, automatic retry loop or new journal.
Changes that return to the same semantic state between captures (ABA), message-only
changes with identical ID/maps, and changes after the final capture are not tracked
as persistent generations. The guarantee is coherent captured metadata plus a
final semantic recheck, not a frozen repository or working-tree transaction.

Directory/name caches last only for their resolution or traversal operation.
External editors and filesystem actors can change files between resolution,
enumeration, hashing and reporting. A disappearance during hashing remains an
I/O error. Parent/namespace swaps, unknown Windows reparse providers and in-place
edits are not completely excluded. No native filesystem snapshot or new durability
guarantee is claimed.

## Scope and verification

No metadata, snapshot or object format changes; no deletion staging, unstage,
rename/replacement commits, restore, history, automatic versioning, batch staging,
streaming capture or Java build enforcement were implemented.

Work started on clean `core-completion` at `72a0b5d`. The Java 21 baseline was
422 cases: 412 successful, zero failures, ten unavailable symbolic-link cases.
Before production changes, 11 new status cases and eight Windows cases failed,
demonstrating the reported defects. One new optional symbolic-link case could not
run. Two old missing-staged-file expectations now require the independent working
deletion while retaining their staged-addition assertions. No baseline case was
dropped.

Final Java 21 verification compiled all 46 main/test source files with
`--release 21` and ran all 20 test classes: **470 cases, 459 successful, zero
failures, eleven assumption-aborted symbolic-link cases**, zero discovery-skipped.
The 48 new cases comprise 36 status/classification/error/interleaving cases and
12 Windows identity cases. Of the new cases, 47 passed and the single tracked
symbolic-file case lacked the required privilege. All new junction, hard-link,
case-sensitive directory and ordinary case-change cases ran successfully.

The original 422 cases remain, including repository preflight, content-object
integrity, serialization, publication/recovery and Windows boundary tests.
Controlled hashing faults establish that read errors are not converted to
deletion, and controlled cooperating mutations establish both unlocked scanning
and retry on changed index or on a new HEAD with the same manifest. Tests also
check read-only storage content and CLI nonzero errors. No administrative ACL
changes or tests against real user repositories were performed.

Final scope is three modified production files, one updated existing test file,
two new test classes and this document. Branch/HEAD remain `core-completion` /
`72a0b5d`; changes are unstaged. No dependency/build settings or persistence
components were changed.
