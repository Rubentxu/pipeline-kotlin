package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob
import dev.rubentxu.pipeline.v2.runtime.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
*/
    fun archive(input: ArchiveArtifactsInput): ArchiveArtifactsResult
}

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
 * The digest and the size are read back FROM the stored target — never supplied by the
 * producer — and the pair is what the durable `ArtifactArchived` event declares: a receipt
 * never describes bytes other than the ones the retention directory held at capture time.
 *
 * The identity it carries is logical ([runId], [stageName], [relPath]) and deliberately
 * holds no physical path: the retention location is resolved by [WorkspaceResolver] at
 * read time, so the receipt survives a relocated control root. The seam binds a run and a
 * stage and nothing finer, so step and attempt identity are explicitly absent — the event
 * emission is what pairs this receipt with its place in the run's history.
 */
data class CaptureReceipt(
    val runId: String,
    val stageName: String,
    val relPath: String,
    val sha256: String,
    val size: Long,
) {
    init {
        require(runId.isNotBlank()) { "CaptureReceipt requires the run identity that produced the capture" }
        require(stageName.isNotBlank()) { "CaptureReceipt requires the stage identity that produced the capture" }
        require(relPath.isNotBlank()) { "CaptureReceipt requires the workspace-relative path of the captured file" }
        // One predicate, owned by the class that mints the format: a receipt whose digest
        // is not the canonical lowercase hex form could never match a re-read target.
        require(Sha256.isSha256Hex(sha256)) {
            "CaptureReceipt requires a verified 64-character lowercase hex digest; " +
                "got '${sha256.take(8)}...' (${sha256.length} chars)"
        }
        require(size >= 0L) { "CaptureReceipt size must be non-negative, got $size" }
    }
}

val ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY: StepCapability =
    StepCapability("artifact-archive.operations")

/**
 * The typed refusal of a capture the retention store cannot honour (escape of the
 * workspace or the retention root, a special node where the capture needs a regular
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
 * the store afterwards. A capture whose stored bytes do not match its source — or whose
 * bytes would reach the store through a symbolic link leaving the workspace or the
 * retention root — is a typed failure with no success event, never a partial success.
*/
class ArchiveArtifactsOperationsAdapter(
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
                entries.add(captureVerified(file, target, relativePath.toString(), retentionRoot))
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
                            relPath = it.relPath,
                            sha256 = it.sha256,
                            size = it.size,
                            archivedAt = java.time.Instant.now(),
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
     * The source is digested BEFORE the copy, and the receipt is only minted from values
     * read back FROM the stored target afterwards: a target that ends holding anything
     * else — a short write, a writer that raced the copy — is a refused capture, never a
     * receipt that lies. When the target already stores exactly those bytes the copy is
     * skipped: REPLACE_EXISTING truncates first, and a re-execution has no reason to open
     * that window again over bytes it is about to write identically.
     */
    private fun captureVerified(
        source: Path,
        target: Path,
        relativePath: String,
        retentionRoot: Path,
    ): CaptureReceipt {
        val expectedSha = Sha256.ofFile(source)
        val expectedSize = Files.size(source)
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
                return verifiedReceipt(relativePath, expectedSha, expectedSize)
            }
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        // Re-read the facts the receipt will state, and refuse the capture when the store
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
        return verifiedReceipt(relativePath, capturedSha, capturedSize)
    }

    private fun readAttributesNoFollow(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun verifiedReceipt(relativePath: String, sha256: String, size: Long): CaptureReceipt =
        CaptureReceipt(
            runId = runIdString,
            stageName = stageIdentity.name,
            relPath = relativePath,
            sha256 = sha256,
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
