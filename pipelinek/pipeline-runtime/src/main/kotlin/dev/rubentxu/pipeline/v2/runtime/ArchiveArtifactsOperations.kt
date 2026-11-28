package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob
import dev.rubentxu.pipeline.v2.runtime.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Typed seam for `core.archiveArtifacts` (/ registry candidate)
 * The ONLY capability the registry-routed `CoreArchiveArtifactsStep.handler` consumes
 * to archive workspace files into the artifacts retention directory.
 * ## Why a typed seam
 * AGENTS.md: effectful work — I/O, files, processes — resides at named
 * ports/adapters, never embedded in a handler. Mirrors the certified pattern:
 * `WorkspaceOperations` / `WorkspaceOperationsAdapter`
 * `ShellOperations` / `ShOperationsAdapter`
 * `DeleteDirOperations` / `DeleteDirOperationsAdapter`
 * ## Scope
 * The seam intentionally hides:
 * - canonical [dev.rubentxu.pipeline.v2.runtime.durable.WorkspaceResolver] —
 *   stage workspace and artefacts-directory resolution (adapter-owned);
 * - filesystem primitives — the adapter is the only place that calls
 *   `Files.copy` / `Files.createDirectories` / sha256;
 * - the event sink — the adapter is the ONLY emitter of `ArtifactArchived` /
 *   `ArtifactArchiveFailed`;
 * - run identity and stage identity — bound by the adapter from the runtime context.
 * ## Failure semantics
 * Implementations return [ArchiveArtifactsResult] (closed typed ADT). Re-classification
 * to [dev.rubentxu.pipeline.v2.domain.StepOutcome] is the responsibility of the handler,
 * which maps `Failed` to the typed failure output (never an exception: a thrown handler
 * is classified ENGINE by the boundary, while archive failures are legacy SCRIPT).
*/
interface ArchiveArtifactsOperations {

    /**
     * Archives workspace files matching the Ant-style [ArchiveArtifactsInput.artifacts]
     * pattern into the run's artefacts retention directory.
     * Each capture's minted identity travels with it into the public channels the
     * adapter feeds — the durable event entry, and, through the step, the wire output
     * and the recorded handle — so a consumer outside the runtime can tell two captures
     * of one visible name apart without ever touching the private retention layout.
*/
    fun archive(input: ArchiveArtifactsInput): ArchiveArtifactsResult

    /**
     * Reads back the captured bytes one [CaptureReceipt] names, from the write-once
     * digest address the retention tree holds them at.
     *
     * The answer is about THE CAPTURE, never about the workspace: the bytes returned
     * are the ones the receipt was minted from, read from the digest address no later
     * capture rewrites — so a consumer that parses what this returns publishes facts
     * about exactly the bytes the receipt's digest names, whatever the workspace does
     * after the capture. The read is ONE authorized opening of the address: the node
     * is opened without following a final-symbolic link, its size is taken from that
     * open, and exactly that many bytes are consumed through the same opening — so
     * the bytes verified are byte-for-byte the bytes delivered, and no swap of the
     * node between a check and a read can put either outside the authorized roots.
     * The read is capped at [MAX_READ_BACK_BYTES] bytes and at the receipt's own
     * size: an address that holds more bytes than the receipt names, fewer than it
     * names, or a receipt naming more than the cap, is refused rather than read.
     * A read that cannot honour the receipt — an address that holds nothing, a
     * non-regular node, bytes that diverge from the receipt's identity — is a typed
     * [CapturedBytesRefusal], never bytes under suspicion. A file store that cannot
     * open without following links answers with a refusal, never with bytes that may
     * have travelled through a link.
     */
    fun openCapture(receipt: CaptureReceipt): CapturedBytesResult

    companion object {
        /**
         * The explicit byte ceiling of one [openCapture] read-back.
         *
         * The read-back holds the whole capture in memory, so the contract states the
         * largest receipt it will answer for instead of letting the machine decide by
         * exhausting itself. A capture beyond this size stays stored and true at its
         * digest address; it is simply not handed out in one read.
         */
        const val MAX_READ_BACK_BYTES: Long = 1_073_741_824L
    }
}

/** The captured bytes, read back verified from the digest address the receipt names. */
class CapturedBytes(val bytes: ByteArray) : CapturedBytesResult

/**
 * The typed refusal to hand out bytes the receipt cannot vouch for.
 *
 * The message names the receipt whose read failed and the fact that failed — the
 * address, the node shape, the size or the digest — so the consumer's refusal carries
 * a truthful diagnostic instead of a suspicion.
 */
class CapturedBytesRefusal(val message: String) : CapturedBytesResult

/** The closed answer of a verified read-back of a capture. */
sealed interface CapturedBytesResult

/** Successful archive outcome with deterministic per-file receipts (no timestamps). */
data class ArchiveArtifactsSuccess(
    val files: List<CaptureReceipt>,
) : ArchiveArtifactsResult

/** Typed archive failure carrying the legacy `FailureKind` (SCRIPT) and message. */
data class ArchiveArtifactsFailed(
    val failureKind: FailureKind,
    val message: String,
) : ArchiveArtifactsResult

sealed interface ArchiveArtifactsResult

/**
 * The verified local capture of one archived file.
 *
 * Three identities, never interchangeable:
 * - [captureId] identifies THE CAPTURE — an identity minted per capture event, equal to
 *   no other capture's, independent of the captured name, path and bytes.
 * - [blobId] identifies THE BYTES — the canonical digest whose write-once address the
 *   retention tree holds them at.
 * - [name] is the visible name the capture was taken under — a description, not an
 *   identity; several captures may share one, so no consumer may resolve a capture by
 *   it alone.
 *
 * The digest and the size are read back FROM the stored target — never supplied by the
 * producer — and the pair is what the durable `ArtifactArchived` event declares: a receipt
 * never describes bytes other than the ones the retention directory held at capture time.
 * The bytes a receipt names live at a digest-addressed, write-once location the archive
 * adapter never rewrites, so a later capture under the same visible name cannot mutate
 * them: a receipt stays true about retrievable bytes for as long as the retention tree
 * holds, and two receipts under one visible name always resolve to their own digest
 * addresses.
 *
 * The run and stage bind the receipt to the context that produced it and it deliberately
 * holds no physical path: the retention location is resolved by [WorkspaceResolver] at
 * read time, so the receipt survives a relocated control root. The seam binds a run and a
 * stage and nothing finer, so step and attempt identity are explicitly absent — the event
 * emission is what pairs this receipt with its place in the run's history.
 */
data class CaptureReceipt(
    val captureId: ArtifactId,
    val runId: String,
    val stageName: String,
    val name: ArtifactName,
    val blobId: BlobId,
    val size: Long,
) {
    init {
        require(runId.isNotBlank()) { "CaptureReceipt requires the run identity that produced the capture" }
        require(stageName.isNotBlank()) { "CaptureReceipt requires the stage identity that produced the capture" }
        require(size >= 0L) { "CaptureReceipt size must be non-negative, got $size" }
    }
}

val ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY: StepCapability =
    StepCapability("artifact-archive.operations")

/**
 * The first path segment the archive adapter reserves for its digest-addressed
 * capture zone. A workspace file whose visible name would land inside it cannot be
 * archived without occupying an address the receipts name, so such a capture is a
 * typed refusal, never a write.
 */
private const val BLOB_ZONE = "_blobs"

/**
 * The typed refusal of a capture the retention store cannot honour (escape of the
 * workspace or the retention root, a visible name that falls inside the reserved
 * digest-addressed zone, a special node where the capture needs a regular
 * file, bytes that do not match their source).
 *
 * It remains an [IllegalStateException] on purpose: [ArchiveArtifactsOperationsAdapter.archive]
 * catches every exception at its boundary and re-emits the refusal as the typed
 * [ArchiveArtifactsFailed] result, so naming the failure changes what a reader of the
 * throw site sees without changing what the handler sees.
 */
internal class ArchiveCaptureRefusal(message: String) : IllegalStateException(message)

/**
 * Canonical input payload of `core.archiveArtifacts` (typed contract)
 * Jenkins-verbatim signature `archiveArtifacts(artifacts, allowEmptyArchive=false,
 * excludes="", fingerprint=false)`. `fingerprint` is carried on the wire for envelope
 * parity but the handler does not act on it (deferral).
*/
data class ArchiveArtifactsInput(
    val artifacts: String,
    val allowEmptyArchive: Boolean = false,
    val excludes: String = "",
    val fingerprint: Boolean = false,
    /**
     * Optional logical name attached to this archive (.ecosystem-local-first)
     * When non-null AND non-blank, the archive's files are recorded in the
     * `ArtifactIndexCapability` under this name, and a subsequent
     * `core.artifact.query(name = ...)` call can retrieve the handle.
     * When null (the default), the archive behaves exactly as before the
     * Files are copied to the retention directory, no index
     * entry is recorded, the legacy byte-shape is preserved.
     * Backward-compatibility:
     *  - The codec includes `name` only when non-null.
     *  - The handler ignores null `name` (no index interaction).
     *  - Existing tests that construct `ArchiveArtifactsInput(artifacts, ...)`
     *    continue to compile and behave the same.
*/
    val name: String? = null,
) {
    init {
        require(artifacts.isNotBlank()) { "core.archiveArtifacts requires a non-blank artifacts pattern" }
        if (name != null) {
            require(name.isNotBlank()) {
                "core.archiveArtifacts 'name' must be null or non-blank (got blank)"
            }
            require('\n' !in name && '\r' !in name) {
                "core.archiveArtifacts 'name' must not contain newlines"
            }
        }
    }
}

/**
 * Production adapter for [ArchiveArtifactsOperations]
 * Reuses the certified `AntStyleGlob` substrate (pipeline-artefacts-local) for pattern
 * matching and the canonical [WorkspaceResolver] for stage-workspace and artefacts
 * retention-directory resolution — mirroring the legacy retention layout
 * `<controlDirRoot>/artifacts/<runId>/<stageName>/`.
 * Legacy-parity semantics preserved verbatim:
 * - empty match + `allowEmptyArchive=false` → `ArtifactArchiveFailed` + typed failure;
 * - empty match + `allowEmptyArchive=true` → success with an EMPTY `ArtifactArchived`
 *   event (legacy emits the event with zero entries);
 * - per-file copy failure → `ArtifactArchiveFailed` + typed failure, no partial success.
 * The glob translation delegates to `AntStyleGlob` (the certified Ant engine) and
 * applies `excludes` after the Jenkins
 * 13-entry default excludes. Each matched file is a verified capture: the source is
 * digested before the copy, identical stored bytes are kept without reopening them, and a
 * changed target is re-copied with the receipt minted only from the values read back from
 * the store afterwards. Every capture also secures its bytes under a digest-addressed,
 * write-once location inside the retention tree —
 * `<artefacts>/<runId>/<stage>/[BLOB_ZONE]/<sha256>` — and the receipt is read back from
 * THAT copy: a later capture under the same visible name lands on its own digest address,
 * so the bytes an emitted receipt names stay retrievable and untouched no matter what the
 * run archives afterwards. The staged copy of an address nobody holds is measured against
 * the digest and size that address names BEFORE it moves into place, so no race with a
 * rewritten source can occupy a digest address with bytes it does not name. A capture
 * whose stored bytes do not match its source — or whose
 * bytes would reach the store through a symbolic link leaving the workspace or the
 * retention root — is a typed failure with no success event, never a partial success.
 * The class is `open` for one reason: [readStoredBytesOnce] is the seam that owns the
 * single authorized opening of a digest address, and a test subclass must be able to act
 * inside the window that seam closes to exercise the swap law deterministically.
*/
open class ArchiveArtifactsOperationsAdapter(
    private val runIdString: String,
    private val stageIdentity: StageIdentity,
    private val controlDirRoot: Path,
    private val eventSink: dev.rubentxu.pipeline.v2.events.EventSink,
    /** Optional project-workspace override (--workspace). */
    private val workspaceBase: Path? = null,
    /**
     * The shared execution location. `null` keeps the previous
     * reconstruction for direct construction; the bridge always supplies it and
     * retiring the fallback is.
*/
    private val executionLocation: dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation? = null,
) : ArchiveArtifactsOperations {

    /**
     * The directory the user pattern is resolved against.
     * The archive source is a user path, so it anchors on
     * CURRENT_DIRECTORY and a `dir(...)` scope moves it. With no scope this is
     * the stage workspace — what the private resolver returned — so behaviour at
     * the root is unchanged.
     * The durable store stays under `controlDirRoot/artefacts/...`; it is an
     * INTERNAL_STORE path and is never a workspace anchor.
*/
    private fun sourceRoot(resolver: WorkspaceResolver): Path {
        val shared = executionLocation?.cwd
        if (shared != null) {
            Files.createDirectories(shared)
            return shared
        }
        return resolver.ensureCreated(resolver.resolve(stageIdentity.name, stageIdentity.index))
    }

    override fun archive(input: ArchiveArtifactsInput): ArchiveArtifactsResult {
        val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
        val workspace = sourceRoot(resolver)

        val matched: List<Path> = try {
            AntStyleGlob(input.artifacts).match(
                root = workspace,
                // The list-in-a-String convention is read by the glob, which knows that a comma
                // inside `{...}` belongs to the pattern and is not a separator. This adapter used
                // to carry its own copy of that split, and it shredded Ant alternation.
                excludes = AntStyleGlob.splitPatternList(input.excludes),
                defaultExcludes = true,
            )
        } catch (e: Exception) {
            val reason = "Glob pattern '${input.artifacts}' failed: ${e.message}"
            eventSink.append(archiveFailedEvent(reason))
            return ArchiveArtifactsFailed(
                failureKind = FailureKind.SCRIPT,
                message = "archiveArtifacts glob failed: ${e.message}",
            )
        }

        if (matched.isEmpty() && !input.allowEmptyArchive) {
            val reason = "No files matched glob pattern '${input.artifacts}' and allowEmptyArchive is false"
            eventSink.append(archiveFailedEvent(reason))
            return ArchiveArtifactsFailed(
                failureKind = FailureKind.SCRIPT,
                message = "archiveArtifacts: no files matched '${input.artifacts}'",
            )
        }

        val artifactDir = resolver.resolveArchiveDir(runIdString, stageIdentity.name)
        val blobDir = artifactDir.resolve(BLOB_ZONE)
        val entries = mutableListOf<CaptureReceipt>()

        return try {
            val workspaceReal = workspace.toRealPath()
            // The containment base is the retention root, not the stage directory: a link
            // planted at or above the stage directory makes that directory's own real path
            // leave the store, so a base taken from it would approve the escape it must
            // refuse. `resolveArchiveDir` fixes the lexical shape
            // `<controlDirRoot>/artefacts/<runId>/<stage>`: two levels up is the root.
            val retentionRoot = Files.createDirectories(artifactDir.parent!!.parent).toRealPath()
            for (file in matched) {
                val relativePath = workspace.relativize(file)
                if (relativePath.nameCount == 0 || relativePath.isAbsolute || relativePath.startsWith("..")) {
                    throw ArchiveCaptureRefusal("archived path '$relativePath' escapes the workspace root")
                }
                if (relativePath.getName(0).toString() == BLOB_ZONE) {
                    throw ArchiveCaptureRefusal(
                        "archived path '$relativePath' names the reserved digest-addressed zone '$BLOB_ZONE/'",
                    )
                }
                // The walk bounded the NAME to the workspace, but a leaf symlink carries the
                // BYTES from wherever it points: the resolved source must live inside the
                // workspace root before any of its bytes are stored.
                if (!file.toRealPath().startsWith(workspaceReal)) {
                    throw ArchiveCaptureRefusal("archive source '$file' resolves outside the workspace root")
                }
                val targetDir = artifactDir.resolve(relativePath.parent ?: Path.of(""))
                Files.createDirectories(targetDir)
                requireStoredInsideRetention(targetDir, retentionRoot)
                val target = targetDir.resolve(relativePath.fileName)
                entries.add(captureVerified(file, target, blobDir, relativePath.toString(), retentionRoot))
            }
            eventSink.append(
                dev.rubentxu.pipeline.v2.events.ArtifactArchived(
                    eventId = java.util.UUID.randomUUID().toString(),
                    runId = runIdString,
                    sequence = 0L,
                    occurredAt = java.time.Instant.now(),
                    files = entries.map {
                        dev.rubentxu.pipeline.v2.events.ArtifactEntry(
                            runId = it.runId,
                            stageName = it.stageName,
                            relPath = it.name.value,
                            sha256 = it.blobId.value,
                            size = it.size,
                            archivedAt = java.time.Instant.now(),
                            captureId = it.captureId.value,
                        )
                    },
                ),
            )
            ArchiveArtifactsSuccess(files = entries.toList())
        } catch (e: Exception) {
            val reason = "Failed to archive: ${e.message}"
            eventSink.append(archiveFailedEvent(reason))
            ArchiveArtifactsFailed(
                failureKind = FailureKind.SCRIPT,
                message = "archiveArtifacts: failed to archive: ${e.message}",
            )
        }
    }

    private fun archiveFailedEvent(reason: String): dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed =
        dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed(
            eventId = java.util.UUID.randomUUID().toString(),
            runId = runIdString,
            sequence = 0L,
            occurredAt = java.time.Instant.now(),
            reason = reason,
        )

    /**
     * Captures one file and returns the receipt of what the store holds afterwards.
     *
     * The source is digested BEFORE any copy. The visible name keeps the legacy
     * semantics — the latest capture's bytes, idempotently kept when identical — and the
     * capture then secures a write-once copy of those bytes at the digest address inside
     * [blobDir]. The receipt is minted ONLY from values read back from that digest
     * address: the bytes a receipt names live at a location no later capture rewrites,
     * so a receipt stays true about retrievable bytes no matter what the run archives
     * under the same visible name afterwards. A capture whose bytes never match what the
     * source promised — visible copy or digest address — is a refused capture, never a
     * receipt that lies.
     */
    private fun captureVerified(
        source: Path,
        target: Path,
        blobDir: Path,
        relativePath: String,
        retentionRoot: Path,
    ): CaptureReceipt {
        val expectedSha = Sha256.ofFile(source)
        val expectedSize = Files.size(source)
        captureVisible(source, target, relativePath, expectedSha, expectedSize, retentionRoot)
        val blob = secureContentBlob(source, blobDir, expectedSha, expectedSize, relativePath, retentionRoot)
        // The digest address is what the receipt names: re-read its facts from the store
        // and refuse the capture when the address does not hold what the source promised —
        // a staged copy that raced, or a pre-existing address holding other bytes.
        if (!readAttributesNoFollow(blob).isRegularFile) {
            throw ArchiveCaptureRefusal(
                "the digest address of '$relativePath' is not a regular stored file",
            )
        }
        val storedSha = Sha256.ofFile(blob)
        val storedSize = Files.size(blob)
        if (storedSha != expectedSha || storedSize != expectedSize) {
            throw ArchiveCaptureRefusal(
                "the digest address of '$relativePath' does not hold the bytes its name promises",
            )
        }
        return verifiedReceipt(relativePath, storedSha, storedSize)
    }

    /**
     * Copies the capture into the visible name, with the semantics the retention layout
     * has always offered: identical bytes keep the stored file closed, changed bytes
     * replace it, and every write is verified against the digest read before the copy.
     */
    private fun captureVisible(
        source: Path,
        target: Path,
        relativePath: String,
        expectedSha: String,
        expectedSize: Long,
        retentionRoot: Path,
    ) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // NOFOLLOW: under that option a symbolic link is not `isRegularFile`, so one
            // predicate refuses both the link — whose write would land outside the store —
            // and any other special node at the position the capture wants for itself.
            val stored = readAttributesNoFollow(target)
            if (!stored.isRegularFile) {
                throw ArchiveCaptureRefusal(
                    "archive target for '$relativePath' is not a regular stored file: refusing to write through it",
                )
            }
            if (Sha256.ofFile(target) == expectedSha && Files.size(target) == expectedSize) {
                return
            }
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        // Re-read what the visible copy became, and refuse the capture when the store
        // does not carry what the source promised. The containment walk repeats too: a
        // link planted between the copy and this read would otherwise leave the receipt
        // describing bytes that are no longer under the retention root.
        requireStoredInsideRetention(target.parent, retentionRoot)
        if (!readAttributesNoFollow(target).isRegularFile) {
            throw ArchiveCaptureRefusal("stored capture for '$relativePath' is not a regular file")
        }
        val capturedSha = Sha256.ofFile(target)
        val capturedSize = Files.size(target)
        if (capturedSha != expectedSha || capturedSize != expectedSize) {
            throw ArchiveCaptureRefusal(
                "captured bytes for '$relativePath' do not match the source after the copy",
            )
        }
    }

    /**
     * Secures the capture's bytes at their digest address, write-once.
     *
     * The address `<blobDir>/<sha256>` is an identity of content, never a mutable
     * position: a file already holding that address is left untouched (two captures of
     * the same content share one address by construction), and only an address nobody
     * holds yet is created — staged through a temp file whose bytes are MEASURED against
     * the digest and size the address names before it is moved atomically. A staged copy
     * that does not match its address is refused, and an address is never occupied by
     * bytes it does not name: a source flipped between this capture's digest and the
     * staged copy would otherwise poison the write-once identity for every later honest
     * capture of that content. A non-regular node planted at the address is refused,
     * not written through.
     */
    private fun secureContentBlob(
        source: Path,
        blobDir: Path,
        expectedSha: String,
        expectedSize: Long,
        relativePath: String,
        retentionRoot: Path,
    ): Path {
        Files.createDirectories(blobDir)
        // The zone the digests name must itself live inside the retention tree: a link
        // planted at the zone's position would otherwise stage bytes outside it.
        requireStoredInsideRetention(blobDir, retentionRoot)
        val blob = blobDir.resolve(expectedSha)
        if (Files.exists(blob, LinkOption.NOFOLLOW_LINKS)) {
            if (!readAttributesNoFollow(blob).isRegularFile) {
                throw ArchiveCaptureRefusal(
                    "digest address for '$relativePath' is not a regular stored file: refusing to write through it",
                )
            }
        } else {
            val staging = Files.createTempFile(blobDir, ".capture-", ".tmp")
            try {
                Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING)
                if (Sha256.ofFile(staging) != expectedSha || Files.size(staging) != expectedSize) {
                    throw ArchiveCaptureRefusal(
                        "the staged copy for '$relativePath' does not hold the bytes its digest address names",
                    )
                }
                Files.move(staging, blob, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                // A concurrent capture of the same content reached the address first:
                // the digest IS the identity, and every write to an address passes the
                // measurement above, so the blob already there names the same bytes
                // this capture verified from its source.
            } finally {
                Files.deleteIfExists(staging)
            }
        }
        return blob
    }

    private fun readAttributesNoFollow(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    /**
     * Reads a capture back from its digest address, verified against the receipt.
     *
     * The address is resolved from the receipt's own run and stage — a receipt names
     * the location its bytes live at, whatever adapter instance answers the read —
     * and the same guards the write applied hold the read: the zone must sit inside
     * the retention tree, the node at the address must be a regular file (a link
     * planted there is refused, never followed), and the bytes must carry the digest
     * the receipt names before any of them leaves the store. Verification and
     * delivery share ONE opening: the digest is computed over the exact bytes
     * [readStoredBytesOnce] consumed, so no node swapped after the read can deliver
     * bytes that were not themselves verified.
     */
    override fun openCapture(receipt: CaptureReceipt): CapturedBytesResult {
        try {
            val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
            val archiveDir = resolver.resolveArchiveDir(receipt.runId, receipt.stageName)
            val blobDir = archiveDir.resolve(BLOB_ZONE)
            val retentionRoot = archiveDir.parent!!.parent.toRealPath()
            requireStoredInsideRetention(blobDir, retentionRoot)
            val blob = blobDir.resolve(receipt.blobId.value)
            if (!Files.exists(blob, LinkOption.NOFOLLOW_LINKS)) {
                return CapturedBytesRefusal(
                    "the digest address of '${receipt.name.value}' holds no capture",
                )
            }
            if (!readAttributesNoFollow(blob).isRegularFile) {
                return CapturedBytesRefusal(
                    "the digest address of '${receipt.name.value}' is not a regular stored file",
                )
            }
            if (receipt.size > ArchiveArtifactsOperations.MAX_READ_BACK_BYTES) {
                return CapturedBytesRefusal(
                    "the receipt of '${receipt.name.value}' names ${receipt.size} bytes, " +
                        "over the read-back ceiling of ${ArchiveArtifactsOperations.MAX_READ_BACK_BYTES}",
                )
            }
            val delivered = readStoredBytesOnce(blob, receipt.size)
            // The digest is taken over THE DELIVERED BYTES — the ones this call read
            // through the single authorized opening — never over a second read of the
            // path, which a node swapped in between could answer for with other bytes.
            val deliveredSha = Sha256.ofBytes(delivered)
            if (deliveredSha != receipt.blobId.value) {
                return CapturedBytesRefusal(
                    "the digest address of '${receipt.name.value}' holds bytes digesting to " +
                        "$deliveredSha, the receipt names ${receipt.blobId.value}",
                )
            }
            return CapturedBytes(delivered)
        } catch (e: Exception) {
            return CapturedBytesRefusal(
                "the capture of '${receipt.name.value}' could not be read back: ${e.message ?: "unknown"}",
            )
        }
    }

    /**
     * Opens the digest address ONCE and consumes exactly [expectedSize] bytes through
     * that single opening.
     *
     * The opening is the whole point: `NOFOLLOW_LINKS` makes the operating system bind
     * the channel to the node the address named at open time (a final-symbolic link is
     * refused at the open, on platforms whose file store supports the option; one that
     * does not throws here and the caller answers with a refusal, never with
     * link-followed bytes), the size is fstat'd from that same channel, and the read
     * cannot be diverted by swapping the node afterwards — the channel holds the node.
     * The consumption is bounded: allocation happens only once the open's own fstat
     * confirms [expectedSize], the loop stops at that many bytes, and one extra probe
     * read refuses a node that GREW beyond the receipt's promise instead of truncating
     * it silently. An address that shrank below the promise is refused the same way.
     *
     * It is `internal open` because the window this closes — between the authorized
     * opening and the delivery of its bytes — cannot be made deterministic by timing a
     * second thread against production code. The test subclass drives the swap through
     * this seam, so the race is decided by call order, never by a sleep.
     */
    internal open fun readStoredBytesOnce(blob: Path, expectedSize: Long): ByteArray {
        FileChannel.open(blob, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val storedSize = channel.size()
            if (storedSize != expectedSize) {
                throw ArchiveCaptureRefusal(
                    "the address holds $storedSize bytes, the receipt names $expectedSize",
                )
            }
            val bytes = ByteArray(expectedSize.toInt())
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw ArchiveCaptureRefusal(
                        "the address yielded ${buffer.position()} of the $expectedSize bytes the receipt names",
                    )
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) > 0) {
                throw ArchiveCaptureRefusal(
                    "the address grew beyond the $expectedSize bytes the receipt names",
                )
            }
            return bytes
        }
    }

    private fun verifiedReceipt(relativePath: String, sha256: String, size: Long): CaptureReceipt =
        CaptureReceipt(
            captureId = ArtifactId.mint(),
            runId = runIdString,
            stageName = stageIdentity.name,
            name = ArtifactName(relativePath),
            blobId = BlobId(sha256),
            size = size,
        )

    /**
     * The bytes of a capture must land under the retention root, physically: a link
     * planted anywhere along the destination resolves the real path out of the store, and
     * [Path.startsWith] over real paths is what says so.
     */
    private fun requireStoredInsideRetention(path: Path, retentionRoot: Path) {
        if (!path.toRealPath().startsWith(retentionRoot)) {
            throw ArchiveCaptureRefusal("archive destination under '$path' resolves outside the retention root")
        }
    }
}
