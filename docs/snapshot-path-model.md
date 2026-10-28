# Snapshot path model (Windows target)

VRN currently targets Windows 11 and Java 21. Historical manifest checks are
deterministic and do not need the original working files. They do not imply that
every NTFS directory is case-insensitive. See the evidence and classifications in
[the Windows 11 compatibility assessment](windows11-path-compatibility.md).

- Stored paths are nonempty, repository-relative Unicode strings with `/` separators.
- Absolute, drive-qualified, UNC/device paths, backslashes, empty components, `.`
  and `..` components are rejected. Loading never cleans up or rewrites a path.
- Java Windows parser exclusions, U+0000–001F and `< > : " | ? *`, are rejected.
  DEL, C1 characters, leading ASCII spaces and non-ASCII whitespace are allowed;
  filenames are never trimmed or Unicode-normalized. Malformed Unicode is rejected.
- Trailing ASCII spaces remain unsupported by the Java Windows parser. Trailing
  periods are rejected because they can resolve to a differently spelled entry.
- The 255 UTF-16 component bound remains the supported NTFS-oriented limit.
  DOS device-like stems, extensions, console aliases and COM/LPT superscripts
  remain an explicit **VRN support limitation**, not a claim that such NTFS files
  cannot exist. Some work through NIO; namespace behavior across component
  positions is not yet safely supported. Device-stem checks trim ASCII spaces
  only: a stem such as `CON` followed by U+2000 is a distinct legal spelling.
- Root `.vrn` and `.vrnauto` names, including their case variants and descendants,
  are reserved. Nested names such as `src/.vrn/example.txt` are ordinary paths.
- A component trie uses Java's locale-independent `String.CASE_INSENSITIVE_ORDER`
  for collision detection. Original metadata spelling is retained, and
  shared-directory capitalization variations (`Src/A`, `src/B`) are accepted.
  Only complete case-equivalent destinations and folded file/descendant conflicts
  remain unsupported. Their diagnostics identify the VRN support boundary.
  This is a conservative lexical policy, not an exact NTFS Unicode upcase-table
  emulation. Legitimate case-distinct files can exist in a case-sensitive directory;
  supporting colliding spellings requires additional identity/metadata design.
  Java 21 Path equality and isSameFile alone cannot establish that capability.
- A file cannot also be a directory prefix, in either insertion order or under
  case-insensitive identity. Parent-plus-staging conflicts are rejected; inherited
  entries are never silently removed. Deletion, replacement, and case-only rename
  semantics remain future work.

The component restrictions are based on Microsoft's
[Windows naming rules](https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file),
with the additional conservative restrictions described above.

## Boundaries

`ProjectFileWalker` checks resolved containment, uses actual directory-entry spelling,
and rejects nonignored symbolic/reparse entries explicitly. Directory resolution
and entry listings are cached only within a scan, not persisted. Hard-link names
remain distinct entries: neither file keys nor isSameFile are used to deduplicate.
This helper is not a per-directory case-flag detector and never changes flags.

`FileScanner` validates collected paths before hashing. `StagingArea` validates
individual input and the resulting index before publication, and checks loaded
index paths. Its existing Path-to-forward-slash conversion remains; a Path object
may already have lost redundant separators during platform parsing. Snapshot
metadata accepts only canonical spelling, rather than repairing such input.

`SnapshotStore` validates before publication and after decoding both current and
supported legacy snapshots. The commit candidate includes all inherited and staged
entries, so it passes this gate before a new manifest, cleanup record or HEAD update.
Snapshot maps are copied and exposed read-only to keep validation and encoding
consistent. Repository-layout checks reuse the same loaded-manifest/index rules.

Valid metadata keeps its format and spelling. Invalid legacy or current metadata
fails clearly and remains untouched; raw files remain available for inspection.
Loading an active snapshot does not audit every historical snapshot or its ancestry.

This establishes lexical consistency, not full recoverability or physical identity.
The scanner checks resolved directories but does not solve every alias or race.
Unresolvable entry aliases fail explicitly. Symbolic links and junctions/reparse
entries are unsupported rather than silently followed. Filesystem-specific Unicode
case tables, absolute destination length limits and concurrent filesystem edits
remain outside this guarantee. Separate hard-link paths are supported as regular
file entries, without promising hard-link topology on future restoration. Object existence and
digest verification belong to Task 6B. Single-index publication stays atomic;
multi-file `add` is still a sequence of operations, not a whole-command transaction.
