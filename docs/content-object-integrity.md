# Content-object integrity (Task 6B)

## Scope and identifiers

Object access uses the existing flat `objects/<id>` layout and raw file contents.
An interpreted object ID is exactly 64 lowercase hexadecimal SHA-256 characters.
Validation precedes filesystem resolution; separators, drives, traversal, uppercase
aliases, unexpected lengths and nonhex tokens are rejected without normalization.
This matches IDs produced by FileHasher and does not change metadata formats.

ObjectStore is bound to one explicitly supplied storage directory. It does not
resolve objects through project filenames, other environments or user-provided
object paths. Manual and future automatic storage can reuse this implementation
with independent directories, guards and state.

## Reuse and writes

`store(id, bytes)` verifies the caller's bytes against the ID before writing.
Under the existing repository mutation guard, an observed existing object is
stream-verified and reused only on success. Corruption never triggers automatic
replacement or repair. New writes use AtomicFilePublisher and are checked from
disk before store reports success. Failed temporary writes retain its existing
cleanup behavior. A late publication error or failed post-publication check may
leave an unreferenced object; it is not silently removed or repaired.

The byte-array write interface is preserved. No extra whole-file clone or buffering
requirement is introduced. Mutable caller buffers are not a supported concurrency
contract; pre/post checks detect inconsistent publication, not arbitrary future
mutation of files by external actors.

## Candidate publication and recovery

CommitService holds the existing storage guard, reconciles prior recovery, loads
staging/parent metadata and builds the complete final manifest. ObjectStore verifies
each distinct final object reference before any new snapshot, cleanup record or
HEAD publication. Both inherited and staged references are included. Superseded
historical objects are not audited simply because an old snapshot mentions them.
There is no persistent successful-verification cache, including when a CommitService
instance is reused. Deduplication lasts only for one verifyAll call.

An integrity failure at this gate publishes no new snapshot and does not advance
HEAD or clear pending staging. Previously published snapshots and object bytes
remain unchanged by validation. Normal reconciliation of an older, unpublished
commit marker can already have retired that marker before validation; this is the
unchanged recovery protocol, not a rollback of the current staging work.

After the gate, the ordering remains snapshot -> pending record -> HEAD -> consumed
staging cleanup. Successful HEAD publication remains the logical commit point;
later cleanup failures remain success with a warning. AtomicFilePublisher,
CommitRecovery and RepositoryMutationGuard protocols are unchanged.

## Verified reads and memory

`verify(id)` reads to EOF and checks SHA-256 with a 64 KiB buffer. No whole object
is loaded into memory. `verifyAll` hashes one copy of each distinct required object
per call, rather than scanning the whole object database or snapshot ancestry.

`openVerified(id)` first performs a complete streaming check before exposing any
bytes. It rewinds the same open handle and returns an InputStream that also checks
the consumed bytes at EOF. The caller must close the stream and read through EOF
for this second check; skip participates in hashing, reset is unsupported, and
both successful EOF and integrity/read failures remain terminal. Later appended
bytes are not exposed after successful EOF. This costs two reads and two digest passes
when fully consumed, deliberately providing pre-exposure validation without a
whole-file memory copy. Callers must publish any derived output only after reading
successfully to EOF. Partial reads or hostile concurrent edits are not an immutable
filesystem snapshot guarantee.

Existing scanning/staging still retains file byte arrays; that older memory issue
is not solved by this task. Incoming write validation hashes an already supplied
array, followed by one streaming verification of the new or reused on-disk object.

## Metadata inspection versus content trust

SnapshotStore remains an immutable **metadata** codec/store, not a full content
audit or activation API. Its load/save operations and RepositoryLayout continue
to support metadata inspection without hashing object contents. Opaque legacy
reference tokens can still be inspected losslessly, but cannot be interpreted as
object paths or activated in a new commit unless the final references satisfy
the strict SHA-256 contract. They are never silently migrated or repaired.

Safe activation must use CommitService, not a direct metadata save followed by a
manual HEAD write. Content consumers must use ObjectStore's verified API, not
assume a successfully parsed manifest proves object availability. A future explicit
full-history audit and restore operation are separate work.

## Windows path and concurrency limits

Storage/object directories and object entries are inspected without following the
final link. Observable symbolic/reparse directories and files are rejected, as are
nonregular object entries. The resolved object-directory parent must be the supplied
storage root. Reads open with NOFOLLOW_LINKS and use the same handle for verification
and consumption. Direct root substitution is rejected before ObjectStore writes or
CommitService acquires its guard. Caller-selected storage ancestry is the anchor.

These are checks, not a security sandbox or portable descriptor-relative transaction.
The repository guard serializes cooperating VRN writers, not arbitrary filesystem
writers. Ancestor replacement can race checks/open; an external destination can
appear between the absent-object check and AtomicFilePublisher's replacement;
external actors can modify bytes after verification or before HEAD publication.
There is no OS-enforced create-only/sealed-object guarantee against such actors.
The EOF check can detect inconsistent bytes consumed after preverification, but
does not make partially consumed data safe to publish before completion.

Hard-link object entries are regular files and may be verified, but another hard
link can mutate the same physical bytes. The next verification detects corruption;
neither file keys nor directory-name existence are trusted as content proof.
Junction/reparse substitution is rejected where observable. Provider-specific
reparse tags, cloud files and adversarial namespace races are not comprehensively
covered. No power-loss durability or fsync guarantee has been added.

## Diagnostics and compatibility

ObjectIntegrityException distinguishes malformed identifier, missing, unreadable,
unexpected type, unsafe path/link, digest mismatch, and write/publication failure.
Without expected-length metadata, truncation and same-length substitution are both
digest mismatches; the verifier does not claim to infer the cause from SHA-256.
Messages identify the object and reason, with control characters escaped in IDs.

Commit CLI integrity failures return 1 and report that this attempt published no
snapshot and did not clear staging. Other errors do not receive that special
guarantee. Add returns 1 on object failure but does not promise whole-command
rollback: earlier files in a multi-file add may already have been staged.

Existing correct SHA-256 objects and snapshots need no migration. Previously
accepted arbitrary object names, mismatching input bytes and corrupt deduplication
hits now fail on access/reuse/new commit. Low-level serialization fixtures remain
metadata-only; tests crossing actual storage/commit boundaries now use real hashes
and stored bytes. The old no-overwrite test additionally requires inconsistent
incoming bytes to fail, while still asserting preservation of the original object.

## Verification

Baseline: clean `develop` at `c20ac4b`; 306 cases, 304 successful and two optional
symbolic-link cases unavailable. Ten new regression cases first failed against
the old implementation, demonstrating the storage and commit integrity gaps.

Final coverage: 357 cases, 353 successful, zero failures, four optional
symbolic-link cases unavailable because of missing Windows privileges. This
includes all 306 baseline cases plus 51 new cases: 17 candidate/CLI/recovery,
28 storage/read/streaming, and six Windows object-path/link cases. The junction
and hard-link cases ran; unreadability and partial reads use deterministic fault
injection rather than administrative ACL changes. Existing path coverage is retained.

The full Gradle suite ran with the host Java 26 runtime. All 41 main/test sources
also compiled with Java 21 `--release 21`, and separate Java 21 JUnit execution
produced the same final results. Gradle itself was not executed on Java 21.

Relevant platform contracts:
[Java 21 Files](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html),
[basic file attributes](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/attribute/BasicFileAttributes.html),
[channel-backed streams](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/Channels.html).
