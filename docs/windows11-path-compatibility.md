# Windows 11 compatibility assessment — Task 6A.1

Phase A was completed before production changes. Baseline: `develop`, `74afd0d`,
266 tests passing. Windows 11 build 26200, NTFS, Temurin 21.0.12.1 were observed.
This assessment distinguishes a name's spelling, stored `/` representation,
directory-entry destination, and underlying file object. They are not interchangeable.

## Existing restrictions and decisions

Categories: **W** Windows/filesystem, **J** Java 21 interface, **I** integrity,
**P** additional VRN support policy. Some rules have more than one justification.

| Existing rule | Category | Windows / Java 21 evidence and integrity risk | Decision |
|---|---|---|---|
| Nonempty relative paths; no absolute, drive-qualified, UNC or device namespace paths | I | A project entry must not change the destination root | Retain |
| `/` stored separators; no backslashes, empty, `.` or `..` components | I | Java accepts/normalizes some spellings, but metadata must not have multiple lexical forms or traversal | Retain; no loading-time repair |
| Root `.vrn` / `.vrnauto`, including case variants, forbidden | I | Storage is not project content even if the filesystem permits the name | Retain; nested names stay legal |
| `< > : " \| ? *` and U+0000–001F forbidden | J / W | Java's Windows parser rejects them; colon also risks drive/stream interpretation | Retain |
| DEL and U+0080–009F forbidden via `isISOControl` | P | Java created and read back U+007F and U+0085 exactly on NTFS; these are not C0 parser exclusions | Remove this excess ban |
| Leading ASCII spaces forbidden | P | Java, PowerShell/.NET and cmd preserved a leading space in the experiment | Remove; never trim filenames |
| Trailing ASCII spaces forbidden | J / I | Java Windows Path parsing rejects these components | Retain |
| Trailing periods forbidden | I / P | Java accepted the input but created a differently spelled entry without the period | Retain as an ambiguity rule, not an NTFS impossibility claim |
| 255 UTF-16 units per component | W / P | NTFS baseline limit; not a universal Java/provider limit | Retain as the supported NTFS-oriented bound; other providers need separate qualification |
| Malformed surrogate sequences forbidden | I | Metadata uses strict Unicode/UTF-8; invalid encoding cannot safely round-trip | Retain |
| DOS device stems, extensions, COM/LPT superscripts and console aliases forbidden | P | `NUL.txt` was a real readable NIO file, but a created `NUL` directory did not permit child creation/read-back in the follow-up probe. Namespace/component-position behavior differs | Retain explicitly as a VRN support limitation pending namespace-safe end-to-end design; do not describe all such files as impossible |
| Strip *all Unicode whitespace* when checking device stems | P | `CON` followed by U+2000 and `.txt` was created/read exactly; it is a distinct non-device stem | Refine to ASCII-space-only device-stem handling |
| Reject shared-directory spelling variations (`Src/A`, `src/B`) | P | Two distinct entries work in one ordinary directory; the complete destinations do not collide | Remove this rejection; preserve both spellings |
| Reject complete case-equivalent destinations | I / P | Genuine collision on ordinary case-insensitive directories; can be distinct in case-sensitive directories | Retain a conservative metadata support boundary, with an explicit VRN limitation diagnostic rather than asserting every NTFS directory is insensitive |
| Reject file/descendant conflicts under the same case-equivalence model | I / P | Exact-prefix contradictions are impossible; folded-prefix contradictions require case-sensitive semantics not currently represented by VRN | Retain; distinguish the folded-case support boundary |
| No Unicode normalization; original spelling retained | I | Normalizing would merge or change names without proof of destination equivalence | Retain |
| Scanner follows regular-file links and silently omits some directory links | P / I | Snapshot format records regular files, not link semantics; aliases can bypass a lexical root check | Refine: explicitly reject nonignored symbolic/reparse entries and check resolved containment |

## Identity and capability findings

Microsoft documents per-directory case-sensitivity flags. A disposable NTFS
directory was enabled successfully for the experiment; **no existing user
directory or drive setting was changed**. Java wrote different contents to
`a.txt` and `A.txt`, enumerated both, and read the correct different contents.
Nevertheless, both `Path.equals` and `Files.isSameFile` returned true. OpenJDK's
Windows provider short-circuits `isSameFile` when lexical Path equality succeeds.
Neither is a reliable destination-identity oracle for this case.

Two hard-link names were enumerated separately while `isSameFile` returned true.
They are separate directory entries and must remain independently versionable.
VRN must not deduplicate manifest paths using file keys or physical-file identity.

Recommended hybrid (full implementation deferred): at initialization read the
provider, FileStore type and supported views as advisory facts. A volume label
such as NTFS does not establish every directory's case semantics. Recheck relevant
directory capabilities at each operation; cache only within that operation,
including the distinction between unknown and known values. Java's standard
Windows views observed here (`basic`, `dos`, `acl`, `owner`, `user`) do not expose
the case flag. Native per-directory discovery and a historical identity contract
need review before case-distinct colliding destinations can be supported.
Do not write a persistent capability cache or change flags in production.

Focused correction: resolved containment is checked during scanning, with
operation-local directory-resolution and entry-listing caches. Actual directory
entry spelling is used; no physical-file identity deduplication occurs. It is not case-flag detection.
Stored manifests remain interpretable without the old working files: lexical
validation stays deterministic and does not probe the current working tree when
loading history. Case-distinct collision support is explicitly deferred rather
than guessed from currently existing files.

## Tool experiments and limits

- Java 21 NIO: creation, enumeration and read-back were tested, not just parsing.
  DOS-like leaf file round-trips succeeded for the tested NUL/CON/PRN/AUX/COM1/LPT1/
  console-alias names, while the `NUL` parent-component test failed with
  NoSuchFileException when creating its child. This is why a blanket namespace
  relaxation is deferred despite the existence of some legitimate such files.
- PowerShell 7/.NET: leading space, trailing period, `NUL.txt`, DEL and Unicode
  whitespace creation were tested. A trailing period was removed from the actual name.
  Microsoft's general whitespace guidance describes normalization of leading
  ASCII spaces too; the current Java 21/Windows 11 read-back retained them. This
  API-dependent evidence supports VRN's relaxation without promising identical
  behavior in every GUI or Win32 namespace.
- cmd: quoted leading-space and trailing-period names were tested; the leading
  space was retained and the trailing period removed. Shell quoting is an input
  convention, not an identity rule.
- File Explorer, VS Code, IntelliJ IDEA and Cursor GUIs were **not tested**.
  VS Code's published new-file source trims tabs/trailing separators, invokes its
  basename validator and warns about leading/trailing whitespace; this is not a
  blanket filesystem prohibition. IntelliJ's new-file action invokes separate
  input/VFS validation and optional extension handling; its complete validation
  chain and installed-version GUI behavior were not exhaustively tested. Cursor's
  dialog policy was not established. Language/template creation can impose other
  application-specific rules.
  No editor-origin attribute determines filesystem identity, and GUI acceptance is
  not a substitute for Java read-back and manifest checks.
- The Get-Volume PowerShell query was denied; Java FileStore reported NTFS instead.
  No dependencies were installed. Experimental files were confined to a fresh
  `vrn-windows-evidence-*` directory under the process temporary directory.

## Explicit baseline-test changes

No baseline test method or safety assertion was deleted. Two existing parameter
rows (leading ASCII space and DEL) moved from rejection to acceptance, reflecting
the observed Java 21 behavior. Three directory-capitalization rejection rows were
replaced with complete case-equivalent file collisions, preserving their failure
safety assertions. New unit/integration tests cover the formerly rejected distinct
`Src/A` / `src/B` destinations, historical interpretation, native names, hard links,
case-sensitive directories, resolved boundaries and explicit link rejection.

## Final verification

The suite contains 306 cases: the 266-case baseline with the documented policy
revisions, 19 new host-independent tests and 21 new Windows filesystem cases.
Both the full Gradle run and separate Java 21 compilation/JUnit execution completed
with 304 successful tests and no failures. Two optional symbolic-link creation
cases were unavailable because the client lacked the required Windows privilege;
the junction, hard-link and disposable case-sensitive-directory cases did run.
No GUI testing or production case-flag changes occurred. Historical spelling is
not migrated; this task does not redesign status matching or restore semantics.

## Sources

- [Windows naming and namespaces](https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file)
- [Microsoft whitespace behavior](https://learn.microsoft.com/en-us/troubleshoot/windows-client/shell-experience/file-folder-name-whitespace-characters)
- [Windows per-directory case sensitivity](https://learn.microsoft.com/en-us/windows/wsl/case-sensitivity)
- [Java 21 Files](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html)
- [Java 21 Path](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html)
- [Java 21 FileStore](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/FileStore.html)
- [OpenJDK Windows path parsing](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsPathParser.java)
- [OpenJDK Windows isSameFile](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileSystemProvider.java)
- [VS Code file-name validation source](https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/files/browser/fileActions.ts)
- [IntelliJ new-file action source](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/ide/actions/CreateFileAction.java)
