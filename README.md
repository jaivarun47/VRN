# VRN

VRN is a local version-control system under development as a final-year academic
project. Its current implementation provides manual file capture and snapshots.
Change-aware automatic versioning is the long-term research goal and is **not yet
implemented**.

## Requirements

- **Windows 11** is the current supported development/test target.
- **Java 21** is the minimum runtime and the verified runtime for filesystem behavior.
- Building requires a **locally installed JDK 21**. Gradle may itself run on Java 21
  or a newer JDK supported by the included wrapper.
- The wrapper pins Gradle 9.6.0. Runtime dependency: Picocli 4.7.7.
  Tests use JUnit 6.0.0.

## Build and test

From the VRN source directory in PowerShell:

```powershell
.\gradlew.bat clean build
```

To run the test suite without cleaning first, use `.\gradlew.bat test`.

The Java toolchain selects an installed JDK 21 for compilation and tests.
Every Java compilation task also uses `--release 21` and UTF-8, restricting the
language/API surface and producing Java 21 class files even when Gradle runs on
a newer JVM. This follows Gradle's [toolchain and release guidance](https://docs.gradle.org/current/userguide/toolchains.html).

Automatic JDK downloads are disabled in `gradle.properties`. If Gradle cannot
find your installed JDK 21, supply its actual installation path:

```powershell
.\gradlew.bat "-Dorg.gradle.java.installations.paths=C:\Path\To\Your\JDK-21" clean build
```

Without a matching JDK 21, Gradle fails with `Cannot find a Java installation`
matching `languageVersion=21` and reports that toolchain auto-provisioning is not
enabled. A newer JDK used to run Gradle does not replace the required JDK 21 toolchain.

First use may download the pinned Gradle distribution and declared dependencies.
With those already cached, use `--offline`. No additional packaging plugin is
required. To choose the Gradle build JVM for one invocation, use
`-Dorg.gradle.java.home=<installed-JDK-path>`; this does not change system Java settings.

The build produces:

```text
build/libs/VRN-1.0-SNAPSHOT.jar
```

This executable JAR includes Picocli; no separate runtime classpath is needed.
`build` runs the full test suite. `test` also builds the JAR and checks its manifest,
Java 21 bytecode, bundled dependency, help/version, and manual CLI workflow using
the Java 21 test JVM. Run these artifact tests through Gradle; it supplies the
generated artifact path as `vrn.executableJar`. Optional symbolic-link tests may
be unavailable when Windows privileges do not permit link creation.

To rebuild only the artifact, run `.\gradlew.bat jar`; that task alone does not
execute tests. After source changes, rebuild before using the JAR.

## Run the CLI

Use a Java 21 runtime. Capture the JAR's absolute path before changing directories:

```powershell
$vrnJar = (Resolve-Path '.\build\libs\VRN-1.0-SNAPSHOT.jar').Path
java -version
java -jar $vrnJar --help
java -jar $vrnJar --version
```

If `java -version` selects another installation, invoke your Java 21 executable
by its full path, for example `& 'C:\Path\To\JDK-21\bin\java.exe' -jar $vrnJar --help`.

Java releases below 21 are unsupported and cannot load the application's Java 21
class files (`UnsupportedClassVersionError`). Later compatible JVMs are permitted,
but filesystem behavior is qualified on Java 21; see the limitations below.

The logical command name is `vrn`; this build does not install a system-wide
`vrn` launcher. Pass the following arguments after `java -jar $vrnJar`:

| Arguments | Purpose |
|---|---|
| `init` | Initialize manual storage in the current project directory; validate and preserve an existing repository. |
| `add <path>` | Capture one file or recursively stage a directory's eligible files. Use `add .` for the current tree. |
| `stage-delete <path>` | Select one tracked file for removal from the next snapshot; preserve its working file. |
| `unstage <path>` | Remove one PUT or DELETE selection; preserve working files and history. |
| `commit -m "message"` | Apply staged PUT/DELETE selections to inherited files and publish a new snapshot. `--message` is also accepted. |
| `status` | Compare HEAD, staged selections, and working files; report staged, unstaged, deleted, and untracked files. |

Root `--help`/`-h` and `--version`/`-V` are supported. Subcommand help flags are
not currently enabled. Commands use the current directory; they do not search
parent directories for a repository. Success returns 0, handled operational
errors return 1, and argument/command errors return 2.

## Basic workflow

Choose a project directory containing files you want to capture, then run:

```powershell
Set-Location 'C:\Path\To\Your\Project'
java -jar $vrnJar init
java -jar $vrnJar status
java -jar $vrnJar add .
java -jar $vrnJar commit -m "Initial snapshot"
java -jar $vrnJar status
```

Selective staging uses, for example, `java -jar $vrnJar add src\Main.java`.
Staging captures the selected bytes: edits made afterward remain unstaged.
Once all current eligible files are committed without further changes, status
reports a clean working directory.

Deletion is explicit and selects only the next snapshot:

```powershell
java -jar $vrnJar stage-delete src\OldFeature.java
java -jar $vrnJar status
java -jar $vrnJar unstage src\OldFeature.java  # Cancel that selection
```

`stage-delete` accepts a tracked file whether present or already missing. It never
removes working files or old snapshots/objects. A retained file appears as untracked
alongside its staged deletion; an absent file is not also an unstaged deletion.
For a staged-only addition, it cancels that PUT instead. Repeated deletion selection
is idempotent; unknown deletions fail. Unstaging an unselected valid file path is a
no-op. Both commands select one repository-relative file; when absent, use its exact
stored spelling. `add` can replace DELETE with PUT, but missing files and `add .`
never automatically select deletions. See [staging intent](docs/staging-intent.md).

Manual state lives in `.vrn/`: raw SHA-256 content objects, full snapshot
manifests, staging, configuration, and HEAD. HEAD identifies the active snapshot.
Required objects are verified before a new snapshot becomes active. Publication
is recoverable around HEAD's commit point; power-loss durability is not guaranteed.

`.vrnignore` contains optional user ignore rules. Supported rules include comments,
literal names, root-relative directory prefixes ending in `/`, and one-`*`
prefix/suffix patterns. A leading `/` anchor, negation, and recursive `**`
semantics are not implemented. Missing rules mean no user filtering. Tracked and
staged files remain visible to status even when ignored. Root `.vrn/` and
`.vrnauto/` are protected independently of ignore rules.

## Current limitations

- Deletion/unstaging selection is single-file only; no recursive, wildcard, or
  multi-path selection is provided. Empty staging cannot commit. Explicit unchanged
  PUTs retain the existing behavior of allowing an identical-content snapshot.
- **History/log and restore commands are not available.** Saved objects and
  manifests do not yet provide a supported restoration workflow.
- **Automatic versioning is not implemented.** `.vrnauto/` is reserved/protected;
  no daemon, monitoring, independent automatic history, or retention exists.
- File/directory replacement commits and complete case-only rename commits are
  unsupported. Status can reconcile supported casing changes without rewriting
  stored spelling.
- Symbolic links, junctions, and other observable reparse entries have limited
  support and can be rejected. Ambiguous case-sensitive destinations are outside
  the current identity model. External filesystem races remain possible.
- The verified runtime is Java 21. An existing project-junction status test fails
  on Java 26; bytecode compatibility does not qualify all newer JVM filesystem
  behavior. The build therefore uses the Java 21 toolchain even on a newer Gradle JVM.
- Multi-file add can leave partial staging on failure. Scanning/hashing still uses
  whole-file buffers. No repository-wide repair, garbage collection, or cross-platform
  qualification is provided.

Focused contracts: [metadata preflight](docs/repository-preflight.md),
[working-tree status](docs/working-tree-status.md),
[content integrity](docs/content-object-integrity.md), and
[Windows path compatibility](docs/windows11-path-compatibility.md).
Older analysis reports describe earlier milestones and may be outdated.
