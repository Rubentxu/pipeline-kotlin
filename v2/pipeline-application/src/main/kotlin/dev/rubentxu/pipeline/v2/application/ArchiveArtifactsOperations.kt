package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob
import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import java.nio.file.Path

/**
 * Typed seam for `core.archiveArtifacts` (LFC-2E1 S2-B10 / G1 registry candidate).
 *
 * The ONLY capability the registry-routed `CoreArchiveArtifactsStep.handler` consumes
 * to archive workspace files into the artifacts retention directory.
 *
 * ## Why a typed seam
 *
 * AGENTS.md STEP IMPLEMENTATION — OPERATIVE GUIDE rule 9 (effectful handlers adapt to
 * typed seams, never embed IO logic). Mirrors the certified pattern:
 * - `WorkspaceOperations` / `WorkspaceOperationsAdapter` (S2-A3 / G1)
 * - `ShellOperations` / `ShOperationsAdapter` (LB-02 / G3)
 * - `DeleteDirOperations` / `DeleteDirOperationsAdapter` (S2-A7 / G3-fix)
 *
 * ## Scope
 *
 * The seam intentionally hides:
 * - canonical [dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver] —
 *   stage workspace and artefacts-directory resolution (adapter-owned);
 * - filesystem primitives — the adapter is the only place that calls
 *   `Files.copy` / `Files.createDirectories` / sha256;
 * - the event sink — the adapter is the ONLY emitter of `ArtifactArchived` /
 *   `ArtifactArchiveFailed`;
 * - run identity and stage identity — bound by the adapter from the runtime context.
 *
 * ## Failure semantics
 *
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

/** Successful archive outcome with deterministic per-file summaries (no timestamps). */
data class ArchiveArtifactsSuccess(
    val files: List<ArchivedFileSummary>,
) : ArchiveArtifactsResult

/** Typed archive failure carrying the legacy `FailureKind` (SCRIPT) and message. */
data class ArchiveArtifactsFailed(
    val failureKind: FailureKind,
    val message: String,
) : ArchiveArtifactsResult

sealed interface ArchiveArtifactsResult

/**
 * Deterministic summary of one archived file. Carries NO timestamps and NO content —
 * the durable `ArtifactArchived` event (with `archivedAt`) is the observability channel.
 */
data class ArchivedFileSummary(
    val relPath: String,
    val sha256: String,
    val size: Long,
)

val ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY: StepCapability =
    StepCapability("artifact-archive.operations")

/**
 * Canonical input payload of `core.archiveArtifacts` (G1 typed contract).
 *
 * Shape mirrors the legacy `CanonicalCoreStepCommand.ArchiveArtifacts` command:
 * Jenkins-verbatim signature `archiveArtifacts(artifacts, allowEmptyArchive=false,
 * excludes="", fingerprint=false)`. `fingerprint` is carried on the wire (legacy
 * envelope parity) but the G1 candidate does not act on it (legacy parity; L7.1 deferral).
 */
data class ArchiveArtifactsInput(
    val artifacts: String,
    val allowEmptyArchive: Boolean = false,
    val excludes: String = "",
    val fingerprint: Boolean = false,
) {
    init {
        require(artifacts.isNotBlank()) { "core.archiveArtifacts requires a non-blank artifacts pattern" }
    }
}

/**
 * Production adapter for [ArchiveArtifactsOperations] (G1).
 *
 * Reuses the certified `AntStyleGlob` substrate (pipeline-artefacts-local) for pattern
 * matching and the canonical [WorkspaceResolver] for stage-workspace and artefacts
 * retention-directory resolution — mirroring the legacy retention layout
 * `<controlDirRoot>/artifacts/<runId>/<stageName>/`.
 *
 * Legacy-parity semantics preserved verbatim:
 * - empty match + `allowEmptyArchive=false` → `ArtifactArchiveFailed` + typed failure;
 * - empty match + `allowEmptyArchive=true` → success with an EMPTY `ArtifactArchived`
 *   event (legacy emits the event with zero entries);
 * - per-file copy failure → `ArtifactArchiveFailed` + typed failure, no partial success.
 *
 * Recorded candidate delta (frozen at G2): the legacy dispatcher used a hand-rolled
 * glob translation and silently IGNORED `excludes`; this candidate delegates to
 * `AntStyleGlob` (the certified Ant engine) and applies `excludes` after the Jenkins
 * 13-entry default excludes. `Files.copy(..., REPLACE_EXISTING)` makes fresh
 * re-execution idempotent. The G2 differential contract freeze owns this decision.
 */
class ArchiveArtifactsOperationsAdapter(
    private val runIdString: String,
    private val stageIdentity: StageIdentity,
    private val controlDirRoot: Path,
    private val eventSink: dev.rubentxu.pipeline.v2.events.EventSink,
    /** WU-LPR-062: optional project-workspace override (--workspace). */
    private val workspaceBase: Path? = null,
) : ArchiveArtifactsOperations {

    override fun archive(input: ArchiveArtifactsInput): ArchiveArtifactsResult {
        val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
        val workspace = resolver.ensureCreated(resolver.resolve(stageIdentity.name, stageIdentity.index))

        val matched: List<Path> = try {
            AntStyleGlob(input.artifacts).match(
                root = workspace,
                excludes = splitExcludes(input.excludes),
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
        val entries = mutableListOf<ArchivedFileSummary>()

        return try {
            for (file in matched) {
                val relativePath = workspace.relativize(file)
                val targetDir = artifactDir.resolve(relativePath.parent ?: Path.of(""))
                java.nio.file.Files.createDirectories(targetDir)
                val target = targetDir.resolve(relativePath.fileName)
                java.nio.file.Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                entries.add(
                    ArchivedFileSummary(
                        relPath = relativePath.toString(),
                        sha256 = sha256Of(target),
                        size = java.nio.file.Files.size(target),
                    ),
                )
            }
            eventSink.append(
                dev.rubentxu.pipeline.v2.events.ArtifactArchived(
                    eventId = java.util.UUID.randomUUID().toString(),
                    runId = runIdString,
                    sequence = 0L,
                    occurredAt = java.time.Instant.now(),
                    files = entries.map {
                        dev.rubentxu.pipeline.v2.events.ArtifactEntry(
                            runId = runIdString,
                            stageName = stageIdentity.name,
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

    private fun splitExcludes(excludes: String): List<String> =
        excludes.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun sha256Of(file: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.nio.file.Files.newInputStream(file).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
