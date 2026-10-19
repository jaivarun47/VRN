# Snapshot path model (Windows target)

VRN's current manifests describe Windows-compatible file destinations. These rules
are explicit and apply on every test host; they do not infer the host's filesystem
case-sensitivity or introduce a separate Linux repository format.

- Stored paths are nonempty, repository-relative Unicode strings with `/` separators.
- Absolute, drive-qualified, UNC/device paths, backslashes, empty components, `.`
  and `..` components are rejected. Loading never cleans up or rewrites a path.
- Components cannot contain control characters or `< > : " | ? *`, malformed
  Unicode, leading/trailing ASCII spaces, or trailing periods. Components are
  limited to 255 UTF-16 code units. Windows device names (including extensions,
  console aliases, and COM/LPT superscript-digit forms) are excluded.
- Root `.vrn` and `.vrnauto` names, including their case variants and descendants,
  are reserved. Nested names such as `src/.vrn/example.txt` are ordinary paths.
- A component trie uses Java's locale-independent `String.CASE_INSENSITIVE_ORDER`
  for collision detection. Original spelling is retained. Case-alias files and
  inconsistent spellings of a shared directory (`Src/a`, `src/b`) are rejected.
  This is a conservative lexical policy, not an exact NTFS Unicode upcase-table
  emulation. Case-sensitive directories are intentionally not supported as an
  exception to this Windows-target contract. Unicode normalization is not applied.
- A file cannot also be a directory prefix, in either insertion order or under
  case-insensitive identity. Parent-plus-staging conflicts are rejected; inherited
  entries are never silently removed. Deletion, replacement, and case-only rename
  semantics remain future work.

The component restrictions are based on Microsoft's
[Windows naming rules](https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file),
with the additional conservative restrictions described above.

## Boundaries

`FileScanner` validates collected paths before hashing. `StagingArea` validates
individual input and the resulting index before publication, and checks loaded
index paths. Its existing Path-to-forward-slash conversion remains; a Path object
may already have lost redundant separators during platform parsing. Snapshot
metadata accepts only canonical spelling, rather than repairing such input.

`SnapshotStore` validates before publication and after decoding both current and
supported legacy snapshots. The commit candidate includes all inherited and staged
entries, so it passes this gate before a manifest, cleanup record or HEAD update.
Snapshot maps are copied and exposed read-only to keep validation and encoding
consistent. Repository-layout checks reuse the same loaded-manifest/index rules.

Valid metadata keeps its format and spelling. Invalid legacy or current metadata
fails clearly and remains untouched; raw files remain available for inspection.
Loading an active snapshot does not audit every historical snapshot or its ancestry.

This establishes lexical consistency, not full recoverability or physical identity.
There are no real-path/alias probes: 8.3 names, hard links, symlinks, junctions,
filesystem-specific Unicode case tables, absolute destination length limits and
concurrent filesystem edits remain outside this guarantee. Object existence and
digest verification belong to Task 6B. Single-index publication stays atomic;
multi-file `add` is still a sequence of operations, not a whole-command transaction.
