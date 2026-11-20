# Add and ignore rules — MT-001 / MT-002

This page records the original repair checkpoint. Task 8B now coordinates the
same selection policy through [atomic batch staging](atomic-batch-staging.md).

This focused post-8A repair began on clean `core-completion` at `d2e1800`.
Task 8A was committed. No user manual-testing repository was accessed or reused;
all execution-based reproduction used newly created temporary repositories.

## Findings and reproduction

MT-001's explicit staging defect was reproduced. Add used the ignore-filtered
`FileScanner.scan` / `ProjectFileWalker.collect` discovery path without consulting
HEAD or the effective staging index. An ignored tracked file could therefore
produce no scanned files and no PUT update. This also affected staged-only additions
and tracked descendants of ignored directory rules.

The exact reported disappearance from status was **not reproduced on this checkout**.
Before the repair, the ignored add was skipped, but `notes.txt` remained visible
as an unstaged modification. Task 7B's independent tracked-path inspection already
preserves this visibility. A dedicated passing regression records that observation;
status implementation changes were not justified by the reproduction evidence.

MT-002's misleading success was reproduced. Add unconditionally printed
`Files staged successfully.` and returned 0 after iterating an empty scan result.
The exclusion of ignored untracked content was existing policy; reporting successful
staging when no selection occurred was the defect. No explicit force/override
policy for untracked files is introduced.

| Scenario | Before repair | After repair |
|---|---|---|
| Modified tracked `notes.txt`, newly ignored, explicit add | Exit 0 and success message; no PUT; still unstaged in this reproduction | Exit 0; PUT captures current bytes; status reports staged modification |
| Ignored untracked `temp/cache.txt`, explicit add | Exit 0 and success message; no PUT | Exit 1; `Failed to add files: Ignored untracked file: ...; no files were staged.` |
| Empty or entirely ignored untracked directory | Exit 0 and success message; no PUT | Exit 0; `No eligible files to stage; the directory is empty or its untracked files match ignore rules.` |

The full original status-disappearance sequence remains unverified. The tests
establish the actual behavior of this checkout and the rebuilt packaged artifact,
without inferring the state or binary used in the earlier manual session.

## Implementation

`FileScanner.scanForAdd` captures HEAD and effective staging membership together
under the existing mutation guard and preflight. Existing discovery still performs
the same traversal, ignore filtering, path containment and internal-directory checks.
The scanner then includes existing known files inside the selected file/directory
scope, independently of ignore rules. PUT, DELETE and staged-only additions all
contribute known membership; absent files never become implicit deletions.

Case-insensitive comparison only identifies lookup candidates. Actual directory
entry spelling and successful `WorkingTreePaths.trackedFile` resolution establish
that a known path is the selected destination. Case-sensitive ambiguity and tracked
reparse paths fail explicitly; hard-linked names remain independent. A project-root
alias remains a supported anchor. Paths outside the selected actual scope are not
included.

The resulting path set is deduplicated, validated and read once per selected file.
`AddCommand` uses this repository-aware scan, rejects an explicit ignored untracked
file and reports a truthful zero-selection directory result. The existing generic
`FileScanner.scan` remains ignore-filtered discovery for its existing callers.

Only these production files change:

- `src/main/java/com/vrn/core/FileScanner.java`
- `src/main/java/com/vrn/cli/AddCommand.java`

No changes are made to `VrnIgnore`, `ProjectFileWalker`, status, staging encoding,
snapshot metadata, repository layout, object storage or commit/recovery logic.
README documents the final command policy. Two new packaged-CLI test classes cover
the findings and associated Windows boundaries:

- `src/test/java/com/vrn/core/AddIgnoreRegressionTest.java`
- `src/test/java/com/vrn/core/WindowsAddIgnoreRegressionTest.java`

## Safety and limits

Existing PUT/DELETE selections are preserved unless selected existing content is
intentionally updated by add. Explicit ignored-untracked rejection and empty directory
selection leave the index unchanged. Ignore rules added/removed later do not rewrite
existing staging intent. Unstage and stage-delete retain their post-8A behavior.

At this repair's checkpoint, coherent membership capture and per-file staging were
separate, so multi-file add could leave partial staging after a later failure.
Task 8B supersedes that limitation: the complete add selection now shares one
guard and one index publication. External namespace/content changes remain
unfrozen. Streaming capture and full case-only rename semantics remain deferred.

## Verification

Gradle 9.6.0 reran the baseline and final clean build using the existing local
Temurin Java 21.0.12.1 installation and offline dependency caches.

| Run | Cases | Successful | Failed | Unavailable |
|---|---:|---:|---:|---:|
| Fresh baseline | 567 | 555 | 0 | 12 |
| Final clean build | 592 | 580 | 0 | 12 |
| New MT regression cases | 25 | 25 | 0 | 0 |

Before production edits, 14 of the initial 17 new cases failed, reproducing the
staging and diagnostic defects; the unignored control, status-visibility check and
internal-protection check passed. All original cases remain unchanged.

The added cases cover exact/directory/wildcard ignore rules, staged-only additions,
current-byte hashes, later working edits, ignore-rule changes, unstaging, PUT/DELETE,
directory scopes, truthful empty selections, `.vrnignore` itself, immutable history,
root internals, actual-case aliases, hard links, case-sensitive ambiguity, tracked
and untracked junctions, and project-root aliases. All new Windows cases ran.
The 12 unavailable cases are existing Windows symbolic-link privilege assumptions;
they are not counted as successes.

An independent packaged-CLI replay ran 24 command checks against the saved pre-fix
JAR and the rebuilt JAR, using two fresh external temporary repositories. It
confirmed both before/after outcomes in the table, preserved staging on the rejected
cache add, preserved historical snapshot bytes, and verified dynamic rules plus
stage-delete/unstage behavior. In both binaries the tracked file stayed visible
after add; only the repaired binary selected its ignored modified contents.

The executable packaging tests passed and all 41 production class files target
Java 21 (major 65) without preview bytecode. Git whitespace checks passed. Changes
remain unstaged and uncommitted; branch, HEAD and Git's index are unchanged. No
Task 8B work or existing user-repository operations were performed.
