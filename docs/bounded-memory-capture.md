# Bounded-memory content capture — Task 8C

CLI `add <path>` now streams source bytes with a reusable 64 KiB buffer. File
contents are not retained in the selected-file list. Task 8B's complete-operation
guard, post-recovery baseline and single staging-index publication are unchanged.

## Initial audit and compatibility

The initial branch was `core-completion`, HEAD
`98044c5d372b7d7dffa1923e623f20a7ad0dcf18`. Task 8B's 14 changed/untracked files
were still uncommitted. They were copied to isolated Temp evidence before work;
no commit, branch switch, reset or discard was performed.

The Java 21 baseline was 651 total, 639 successful, 0 failed, 12 unavailable
Windows symlink-privilege cases. Runtime: Temurin 21.0.12.1; Gradle 9.6.0;
Windows 11 NTFS. The Gradle wrapper launcher uses the installed Oracle Java 26,
while the build/test toolchain uses Java 21 and `--release 21`.

Previously `AddService -> FileScanner.scanForAddLocked -> readFiles ->
FileHasher.hashWithBytes` called `InputStream.readAllBytes`, then retained every
`ScannedFile.bytes` array until the whole batch completed. Live content memory
grew with the sum of selected file sizes, with additional allocation overhead
while reading. `FileHasher.hash` also delegated to byte-returning capture, so
status had memory growth proportional to its largest inspected file.

Object verification, verified object reads and commit candidate verification
already used streaming digests. Those contracts are preserved. `MetadataPaths`
still reads metadata bytes in memory; metadata growth depends on entry count,
not on source content length. The explicit byte-returning `hashWithBytes`,
`ScannedFile` and scanner compatibility APIs remain buffered for their existing
callers; CLI add and status no longer use those paths. `ObjectStore.store(id,
bytes)` remains available without a new format or implicit migration.

## Pipeline and publication

1. Under the existing repository guard, reconcile pending commit recovery and
   capture the index baseline. Discover eligible actual source paths, recorded
   PUT spellings and selection-time attributes; retain metadata only.
2. For each file sequentially, revalidate supported path/type/identity and source
   attributes. Create a unique `.capture-*.tmp` inside the protected object store.
3. Open the source without following the leaf link. Recheck attributes after
   opening. Feed each fixed-size chunk into SHA-256 and the temporary output.
   Count actual bytes using a `long`. Close both handles at EOF.
4. Check byte count, size, last-write time, creation time and available file key
   against selection/opening observations. Reject detectable instability.
5. Independently stream-hash the prepared object. Only matching prepared bytes
   can proceed. Resolve the strict lowercase SHA-256 destination.
6. Verify/reuse an existing object. Otherwise create its final entry with
   `Files.createLink(destination, temporary)`, then stream-verify the published
   object. A collision is verified, never overwritten. Remove only this capture's
   temporary entry; the published object remains.
7. Merge `PUT(recordedPath, digest)` into the in-memory candidate. After every
   selected object is prepared, validate the complete candidate and publish the
   staging index once through the existing atomic-replacement mechanism.

The 64 KiB choice matches the existing object verifier, provides bounded copy
memory and performs adequately in the measurements below. It is not claimed to
be an optimal buffer size for all disks. The same capture buffer is reused across
files; streaming verification uses additional small bounded buffers. There is
no parallel capture, compression or source-sized allocation in CLI add.
The pipeline needs disk space for the current prepared file. Even a reused object
is preceded by one source capture into a temporary file, because its digest is
not known until EOF; reuse avoids a second published object, not that preparation.

### Why create-new hard-link publication

The byte-array publisher cannot accept an already-streamed file without buffering
it again. Its metadata move also explicitly replaces the destination; Java atomic
move does not provide a portable conditional fail-if-present guarantee. Streamed
objects therefore use a narrow `AtomicFilePublisher.linkObject` seam. It creates
a directory entry for already closed, verified bytes with fail-if-present
semantics, including a destination appearing after the initial existence check.

The temporary and destination are siblings on the same filesystem; no cross-volume
move is attempted. NTFS hard links were verified both directly and through Java
integration tests. Unsupported hard links fail explicitly with the index unchanged;
there is no copying into a visible hash filename, replace-existing fallback or
new index publication protocol. This introduces a filesystem capability requirement
for new streamed objects; other filesystems/platforms are not newly qualified.

Raw object bytes, SHA-256 names, repository configuration, snapshots, v1/v2 staging,
PUT/DELETE representation and CLI syntax remain compatible. Historical objects
written through the previous byte-array API can be verified and reused unchanged.

## Source consistency and Windows limits

`ContentCapture.observe` uses existing component-by-component `WorkingTreePaths`
checks and non-following attributes. It rejects missing/nonregular sources,
case-spelling changes, ambiguous case identities, junctions and reparse entries.
Opening uses `NOFOLLOW_LINKS`; pre/post-open observations narrow substitution
races. File keys are used only as source stability indicators, never to collapse
hard-linked project names or determine logical path identity.

Detected source replacement, truncation, growth, timestamp changes, type changes,
read failures or disappearance abort the batch and request retry. A file modified
after successful capture remains a later working change; its earlier captured
bytes stay staged. Captures of different files occur at different times.

Matching indicators do not prove that the source was unchanged. Same-size rewrites,
timestamp manipulation, replacement between path observations and handle opening,
and parent namespace changes can evade the checks. Java's path-based Windows APIs
do not provide descriptor-relative snapshot isolation. Hostile external mutation
of temporary or published objects is also outside the cooperating-writer guard.
This task does not freeze the working directory or promise a simultaneous snapshot.

## Failures and cleanup

Read, source-stability, temporary-write, prepared-verification and publication
errors abort before index replacement. The baseline's raw index bytes (or absence)
remain authoritative, preserving prior PUTs/DELETEs and selections that would have
been replaced. Recovery can legitimately retire a consumed index before that
baseline is established, exactly as in Task 8B.

Both source and temporary handles are closed. Normal failure cleanup deletes only
the unique temporary path. Cleanup errors are retained as a primary or suppressed
failure; an inaccessible temporary can remain and require inspection. They do not
justify deleting a published digest object. Verified objects prepared earlier in
a failing batch remain harmlessly unreferenced, pending future reachability cleanup.

Index replacement/outcome handling, raw-index recovery fingerprints, HEAD's commit
point and historical snapshots are unchanged. Atomic visibility and process-crash
consistency do not imply sudden-power-loss durability; no fsync protocol is added.

## Regression and memory evidence

Before production edits, both new packaged tests failed with `OutOfMemoryError:
Java heap space` inside `ChannelInputStream.readAllBytes` using the saved Task 8B
implementation. They generated fixtures incrementally, without source-sized test
arrays. After streaming implementation both completed under Java 21 `-Xmx64m`:

| Selection | Source bytes | Measured add duration (initial targeted run) |
|---|---:|---:|
| One file | 167,772,160 (160 MiB) | 1,461 ms |
| Directory with two files | 83,886,080 each (2 × 80 MiB) | 1,714 ms |

Each test independently stream-hashes sources and objects, checks index hashes,
repeats staging/reuse under the same heap cap, runs status and commit, checks HEAD
and historical content, edits a working file and exercises add/unstage. This proves
these files work with the specified heap; it does not establish an exact peak or
a universal memory bound for arbitrary path/metadata counts.

### Small before/after benchmark

`CaptureBenchmarkProbe` was run in separate Java 21 processes with `-Xmx256m`,
identical deterministic 32 MiB inputs and fresh repository/object stores. Order:
old/new/old/new. Capture time measures the complete AddService call, including
validation, object preparation/verification and index publication, excluding JVM
startup and fixture generation. A 5 ms sampler records Java heap used; these are
sampled peaks, not exact peaks, native-memory/RSS measurements or cold-cache tests.

| Implementation | Capture runs | Sampled heap peak in both runs | Additional object verification |
|---|---|---:|---|
| Saved Task 8B | 523 / 513 ms | 39 MiB | 66 / 60 ms |
| Task 8C | 468 / 461 ms | 6 MiB | 66 / 61 ms |

Each run created one correct hash object and published the index exactly once.
The measured reduction concerns sampled Java heap on this workload; two warm/cache-
influenced runs do not prove generally faster throughput. The main scalability
evidence is bounded chunk processing plus the larger-than-heap regression tests.

### Reproduce

```powershell
.\gradlew.bat '-Dorg.gradle.java.home=C:\Users\Oggy\.jdks\temurin-21.0.12.1' --gradle-user-home 'C:\Users\Oggy\.gradle' --offline --no-daemon --console=plain test --tests com.vrn.core.StreamingCaptureCliTest
```

This builds the executable and generates the 160 MiB / two 80 MiB fixtures in
isolated JUnit Temp directories, launching the packaged executable with `-Xmx64m`.
It installs nothing. The standalone benchmark script/results and before-change
JAR are retained as disposable evidence under `%TEMP%/VRN_Task8C_0bdbe5d5`.

## Changed components

| Component | Change |
|---|---|
| `SelectedFile` (new) | Source path, recorded path and selection-time attributes; no bytes. |
| `ContentCapture` (new) | Reusable 64 KiB stream, incremental digest, no-follow open, stability checks and deterministic I/O seams. |
| `FileScanner` | Separates metadata-only selection from legacy buffered scanner results. |
| `ObjectStore` | Prepared-object capture, independent verification, collision-safe publication/reuse and temporary cleanup under the existing guard. |
| `AtomicFilePublisher` | Adds the narrow create-new object-link seam; metadata replacement remains unchanged. |
| `AddService` | Captures each selected source through ObjectStore and merges its digest; preserves the batch transaction. |
| `FileHasher` | Hash-only calls stream; byte-returning compatibility calls retain their explicit buffering contract. |
| `ScannedFile` | Comment clarifies its compatibility-only byte-retention role. |
| `AtomicBatchStagingTest` | Moves capture/object failure seams to the streamed APIs, retaining all 28 transaction cases and raw-index/history assertions. |
| `StreamingCaptureTest`, `StreamingCaptureCliTest` (new) | Buffer edges, digest correctness, reuse/corruption/collisions, source races, cleanup/resource failures, and constrained-heap executable workflows. |
| `CaptureBenchmarkProbe` (new test helper) | Identical old/new capture timing, sampled heap and publication-count measurement. |
| README and focused integrity/batch docs | Current capture guarantees and historical-checkpoint corrections. |

Production components above are in `src/main/java/com/vrn/core/`; the test classes
and benchmark harness are in `src/test/java/com/vrn/core/`. Existing CLI, staging,
commit and recovery production files were not changed further from Task 8B.

## Final verification and remaining work

The clean offline Java 21 build passed in 12m11s:

| Check | Result |
|---|---|
| Baseline | 651 total; 639 successful; 0 failed; 12 unavailable. |
| Final full suite | 685 total; 672 successful; 0 failed; 13 unavailable; 32 test classes. |
| New Task 8C cases | 32 streaming core + 2 constrained-heap CLI cases: 33 successful, 1 unavailable. |
| Initial targeted runs | 43 heap/batch cases successful; 30 streaming core cases successful and 1 unavailable. The additional parent-junction case subsequently passed in the full run. |
| Task 8B regressions | All 28 core and 13 packaged CLI cases successful. |
| MT-001/002/003 | All 25 ignore/add cases and all 18 case-only regression cases successful. |
| Unavailable cases | Original 12 Windows symlink-privilege assumptions plus the new leaf-symlink substitution assumption; no unavailable cases counted as successes. |
| Packaged bytecode | All 46 application classes: major 65, minor 0; executable tests reported Java 21.0.12.1. |
| JAR SHA-256 | `79A32F78A78C6E66D793D3837E4281172A6108D1F97643710BFE78F93C92E447` |

After the clean build, a separate 128 MiB packaged-CLI smoke test ran all 14
commands with `-Xmx64m` in a new Temp repository. It checked initialization, add,
status, commit, a later working edit, mixed PUT/DELETE preservation, ignored
tracked inclusion, ignored untracked rejection with identical index bytes,
HEAD/history preservation during add, correct committed selection rather than
later working bytes, and add/unstage. All 8 stored objects were independently
SHA-256 verified, including historical objects and the object left after unstage.

Baseline, before-change OOM, targeted and final XML reports, old/new benchmark
logs/results, smoke command logs and scripts are retained under
`%TEMP%/VRN_Task8C_0bdbe5d5`. The normal report is
`build/reports/tests/test/index.html`. The saved old JAR is `before.jar`; the
benchmark can be repeated with two separately initialized roots containing the
same input and the standalone harness:

```powershell
# Set $java21, $testClasses, $candidateJar and $freshRoot to actual local paths.
& $java21 '-Xmx256m' '-cp' "$testClasses;$candidateJar" 'com.vrn.core.CaptureBenchmarkProbe' $freshRoot 'input.bin'
```

Compile the harness with the existing Gradle `testClasses` task. Use the saved
old JAR and current JAR in separate processes; do not add production class folders
ahead of the selected JAR on the classpath. `BenchmarkCapture.ps1` in the evidence
directory records exact incremental fixture generation and run ordering.

Branch/HEAD and the Git index fingerprint remained unchanged. Task 8B and Task
8C changes remain uncommitted on `core-completion`. Existing user Desktop testing
repositories were neither read nor modified.

The core is ready for a separately authorized Task 8D assessment/implementation.
Full case-only rename and file/directory transition semantics remain unsupported.
Residual source/namespace races, metadata/path-count memory growth, temporary
disk needs, filesystem hard-link availability, power-loss durability and future
unreachable-object cleanup remain explicit limitations. Task 8D was not started.
