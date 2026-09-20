package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RestoredEntry
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.StashFailed as StashFailedEvent
import dev.rubentxu.pipeline.v2.events.StashRestored
import dev.rubentxu.pipeline.v2.events.StashedEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Production adapter for [StashOperations] (WU-LPR-089 / Tier B #1).
 *
 * Storage layout: `<controlDirRoot>/stashes/<runId>/<name>/`. The stash directory
 * is a SIBLING of `<controlDirRoot>/workspace/...` and `<controlDirRoot>/artefacts/...`,
 * so `WorkspaceResolver.cleanupAfterComplete()` does NOT destroy stashes when the
 * producing stage finishes (F-ARCH-L7 workspace-cleanup integrity preserved).
 *
 * The adapter is the ONLY place that:
 * - reads/writes the stash directory on disk,
 * - computes per-file sha256 / size,
 * - emits `StashCreated` / `StashRestored` / `StashFailed` events.
 *
 * The handler reaches ONLY [StashOperations] via the [STASH_OPERATIONS_CAPABILITY]
 * typed seam — it never touches `Files`, `MessageDigest`, or `EventSink` directly.
 */
class StashOperationsAdapter(
    private val runIdString: String,
    private val stageIdentity: StageIdentity,
    private val controlDirRoot: Path,
    private val eventSink: EventSink,
    /** WU-LPR-062 parity: optional project-workspace override (--workspace). */
    private val workspaceBase: Path? = null,
) : StashOperations {

    override fun stash(input: StashInput): StashResult {
        val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
        val workspaceRoot = resolver.ensureCreated(
            resolver.resolve(stageIdentity.name, stageIdentity.index),
        )

        val matched: List<Path> = try {
            AntStyleGlob(input.includes).match(
                root = workspaceRoot,
                excludes = splitExcludes(input.excludes),
                defaultExcludes = true,
            )
        } catch (e: Exception) {
            val reason = "Glob pattern '${input.includes}' failed: ${e.message}"
            eventSink.append(stashFailedEvent(input.name, "stash", reason))
            return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
        }

        if (matched.isEmpty()) {
            val reason = "stash '${input.name}' matched no files for pattern '${input.includes}'"
            eventSink.append(stashFailedEvent(input.name, "stash", reason))
            return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
        }

        val stashRoot = stashRoot(runIdString, input.name)
        return try {
            // Recreate the stash root (idempotent: a fresh stash overwrites a previous one).
            if (Files.exists(stashRoot)) {
                Files.walk(stashRoot).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
            Files.createDirectories(stashRoot)

            val entries = mutableListOf<StashedEntry>()
            for (file in matched) {
                val rel = workspaceRoot.relativize(file)
                val target = stashRoot.resolve(rel.toString())
                Files.createDirectories(target.parent ?: stashRoot)
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                entries.add(
                    StashedEntry(
                        relPath = rel.toString(),
                        sha256 = sha256Of(target),
                        sizeBytes = Files.size(target),
                    ),
                )
            }

            eventSink.append(
                StashCreated(
                    eventId = UUID.randomUUID().toString(),
                    runId = runIdString,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stageName = stageIdentity.name,
                    name = input.name,
                    files = entries.toList(),
                ),
            )
            StashSuccess(entries = entries.toList())
        } catch (e: Exception) {
            val reason = "stash '${input.name}' failed: ${e.message}"
            eventSink.append(stashFailedEvent(input.name, "stash", reason))
            StashFailed(failureKind = FailureKind.INFRASTRUCTURE, message = reason)
        }
    }

    override fun unstash(input: UnstashInput): StashResult {
        val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
        val workspaceRoot = resolver.ensureCreated(
            resolver.resolve(stageIdentity.name, stageIdentity.index),
        )

        val stashRoot = stashRoot(runIdString, input.name)
        if (!Files.exists(stashRoot)) {
            val reason = "unstash '${input.name}' failed: no such stash in run $runIdString"
            eventSink.append(stashFailedEvent(input.name, "unstash", reason))
            return StashFailed(failureKind = FailureKind.USER, message = reason)
        }

        val restoreRoot = if (input.into.isNullOrBlank()) {
            workspaceRoot
        } else {
            val target = workspaceRoot.resolve(input.into).normalize()
            // Defense-in-depth: the typed contract already forbids ".." segments and
            // absolute paths, but we re-check at the adapter as a Zip-Slip guard.
            if (!target.startsWith(workspaceRoot)) {
                val reason = "unstash '${input.name}' 'into' path escapes workspace: '${input.into}'"
                eventSink.append(stashFailedEvent(input.name, "unstash", reason))
                return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
            }
            Files.createDirectories(target)
            target
        }

        return try {
            val entries = mutableListOf<RestoredEntry>()
            Files.walk(stashRoot).use { stream ->
                stream.filter(Files::isRegularFile).forEach { src ->
                    val rel = stashRoot.relativize(src)
                    val dst = restoreRoot.resolve(rel.toString())
                    Files.createDirectories(dst.parent ?: restoreRoot)
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                    entries.add(
                        RestoredEntry(
                            relPath = rel.toString(),
                            sha256 = sha256Of(dst),
                            sizeBytes = Files.size(dst),
                        ),
                    )
                }
            }

            eventSink.append(
                StashRestored(
                    eventId = UUID.randomUUID().toString(),
                    runId = runIdString,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stageName = stageIdentity.name,
                    name = input.name,
                    entries = entries.toList(),
                ),
            )
            StashRestoredResult(entries = entries.toList())
        } catch (e: Exception) {
            val reason = "unstash '${input.name}' failed: ${e.message}"
            eventSink.append(stashFailedEvent(input.name, "unstash", reason))
            StashFailed(failureKind = FailureKind.INFRASTRUCTURE, message = reason)
        }
    }

    /** Resolves the durable per-run per-name stash root. */
    private fun stashRoot(runIdString: String, name: String): Path {
        val stashesRoot = controlDirRoot.resolve("stashes").resolve(runIdString).resolve(name)
        return stashesRoot
    }

    private fun stashFailedEvent(name: String, op: String, reason: String): StashFailedEvent =
        StashFailedEvent(
            eventId = UUID.randomUUID().toString(),
            runId = runIdString,
            sequence = 0L,
            occurredAt = Instant.now(),
            stageName = stageIdentity.name,
            name = name,
            operation = op,
            reason = reason,
        )

    private fun splitExcludes(excludes: String): List<String> =
        excludes.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun sha256Of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { stream ->
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
