package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.ArtifactEntry
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/** Runtime dependencies required to dispatch one canonical archiveArtifacts node. */
data class CanonicalArchiveArtifactsDispatchContext(
    val runId: String,
    val stageName: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val controlDirRoot: Path?,
    val eventSink: EventSink,
    val workspaceRoot: Path,
)

/**
 * Dispatches canonical `core.archiveArtifacts` nodes.
 *
 * Globs the artifacts pattern against the workspace and copies matched files
 * to the artifacts retention directory, then emits ArtifactArchived event.
 */
class CanonicalArchiveArtifactsNodeDispatcher {

    fun dispatch(
        command: CanonicalCoreStepCommand.ArchiveArtifacts,
        context: CanonicalArchiveArtifactsDispatchContext,
    ): StepOutcome {
        val workspace = context.workspaceRoot

        // Glob artifacts against workspace
        val glob = command.artifacts
        val matchedFiles: List<Path> = try {
            Files.walk(workspace)
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().matches(globToRegex(glob)) }
                .toList()
        } catch (e: Exception) {
            context.eventSink.append(
                ArtifactArchiveFailed(
                    eventId = java.util.UUID.randomUUID().toString(),
                    runId = context.runId,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    reason = "Glob pattern '$glob' failed: ${e.message}",
                ),
            )
            return StepOutcome.Failure(
                PipelineFailure(FailureKind.SCRIPT, "archiveArtifacts glob failed: ${e.message}"),
            )
        }

        if (matchedFiles.isEmpty() && !command.allowEmptyArchive) {
            context.eventSink.append(
                ArtifactArchiveFailed(
                    eventId = java.util.UUID.randomUUID().toString(),
                    runId = context.runId,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    reason = "No files matched glob pattern '$glob' and allowEmptyArchive is false",
                ),
            )
            return StepOutcome.Failure(
                PipelineFailure(FailureKind.SCRIPT, "archiveArtifacts: no files matched '$glob'"),
            )
        }

        // Copy files to artifacts retention directory
        val artifactDir = context.controlDirRoot
            ?.resolve("artifacts")
            ?.resolve(context.runId)
            ?.resolve(context.stageName)
            ?: return StepOutcome.Success

        val artifactEntries = mutableListOf<ArtifactEntry>()

        for (file in matchedFiles) {
            try {
                val relativePath = workspace.relativize(file)
                val targetDir = artifactDir.resolve(relativePath.parent ?: Path.of(""))
                Files.createDirectories(targetDir)
                val target = targetDir.resolve(relativePath.fileName)
                Files.copy(file, target)

                val sha256 = computeSha256(target)
                val size = Files.size(target)

                artifactEntries.add(
                    ArtifactEntry(
                        runId = context.runId,
                        stageName = context.stageName,
                        relPath = relativePath.toString(),
                        sha256 = sha256,
                        size = size,
                        archivedAt = Instant.now(),
                    ),
                )
            } catch (e: Exception) {
                context.eventSink.append(
                    ArtifactArchiveFailed(
                        eventId = java.util.UUID.randomUUID().toString(),
                        runId = context.runId,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        reason = "Failed to archive ${file.fileName}: ${e.message}",
                    ),
                )
                return StepOutcome.Failure(
                    PipelineFailure(FailureKind.SCRIPT, "archiveArtifacts: failed to archive ${file.fileName}: ${e.message}"),
                )
            }
        }

        // Emit ArtifactArchived event
        context.eventSink.append(
            ArtifactArchived(
                eventId = java.util.UUID.randomUUID().toString(),
                runId = context.runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                files = artifactEntries,
            ),
        )

        return StepOutcome.Success
    }

    private fun globToRegex(glob: String): Regex {
        val regex = glob
            .replace(".", "\\.")
            .replace("**/", ".*")
            .replace("*", "[^/]*")
            .replace("?", "[^/]?")
        return Regex("^$regex$")
    }

    private fun computeSha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = Files.readAllBytes(file)
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
