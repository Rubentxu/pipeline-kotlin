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
import java.io.IOException
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

        // WU-RP-012 — paths confinement for stash.
        // Resolve the workspace's real path so we can detect symlinks that
        // escape (or point to an external location) at any level.
        val workspaceRootReal: Path = try {
            workspaceRoot.toRealPath()
        } catch (e: IOException) {
            // INFRASTRUCTURE: the workspace itself cannot be resolved.
            val reason = "stash workspace root cannot be resolved to a real path: ${e.message}"
            eventSink.append(stashFailedEvent(input.name, "stash", reason))
            return StashFailed(failureKind = FailureKind.INFRASTRUCTURE, message = reason)
        }
        // Reject if the workspace itself is a symlink (defence-in-depth; rare).
        if (Files.isSymbolicLink(workspaceRoot)) {
            val reason = "stash workspace '$workspaceRoot' is a symlink; refusing to follow"
            eventSink.append(stashFailedEvent(input.name, "stash", reason))
            return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
        }

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

        // WU-RP-012 — symlink filter. After AntStyleGlob.match (which uses
        // Files.walk with FOLLOW_LINKS=true), every `matched` path may resolve
        // to an external target via a symlink. We reject:
        //   (a) any matched path that IS a symlink (refuses to copy symlink targets);
        //   (b) any matched path whose real path falls outside workspaceRootReal.
        // Both checks are necessary: a symlink-to-file can survive check (a)
        // depending on platform (e.g. broken symlinks), and a symlink-to-dir
        // can put regular files into `matched` via follow-links walk.
        for (file in matched) {
            if (Files.isSymbolicLink(file)) {
                val reason = "stash '${input.name}' matched a symlink at '$file'; refusing to follow symlinks"
                eventSink.append(stashFailedEvent(input.name, "stash", reason))
                return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
            }
            val fileReal: Path = try {
                file.toRealPath()
            } catch (e: IOException) {
                val reason = "stash '${input.name}' matched path '$file' that cannot be resolved (broken symlink?): ${e.message}"
                eventSink.append(stashFailedEvent(input.name, "stash", reason))
                return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
            }
            if (!fileReal.startsWith(workspaceRootReal)) {
                val reason = "stash '${input.name}' matched path '$file' (real '$fileReal') which escapes the workspace (real '$workspaceRootReal'); refusing to follow symlinks"
                eventSink.append(stashFailedEvent(input.name, "stash", reason))
                return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
            }
        }

        val stashRoot = stashRoot(runIdString, input.name)
        // Resolve stashRoot's real path too: the stash archive itself must not
        // be a symlink (otherwise `relativize` and `target = stashRoot.resolve(rel)`
        // would silently follow it on copy). Defence-in-depth.
        val stashRootReal: Path = try {
            stashRoot.toRealPath()
        } catch (e: IOException) {
            // stashRoot does not exist yet — `toRealPath` of an absent path resolves
            // the existing parent chain. This should not throw for the staged layout,
            // but capture defensively.
            stashRoot.toAbsolutePath().normalize()
        }
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
                // Defence-in-depth: target MUST stay inside stashRootReal. Even
                // though `rel` is derived from `workspaceRootReal`-bound files,
                // a poisoned glob or an unusual lexical layout could in principle
                // produce a rel path that resolves outside. Reject fail-closed.
                val targetReal = try {
                    target.toRealPath()
                } catch (e: IOException) {
                    target.toAbsolutePath().normalize()
                }
                if (!targetReal.startsWith(stashRootReal)) {
                    val reason = "stash target '$target' (real '$targetReal') escapes stashRoot (real '$stashRootReal'); refusing"
                    eventSink.append(stashFailedEvent(input.name, "stash", reason))
                    return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
                }
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

        // WU-RP-012 — paths confinement for unstash.
        // The stashRoot itself MUST NOT be a symlink (otherwise the walk below
        // could follow it). Resolve real paths for both stashRoot and workspaceRoot.
        val stashRootReal: Path = try {
            stashRoot.toRealPath()
        } catch (e: IOException) {
            val reason = "unstash stashRoot '$stashRoot' cannot be resolved to a real path: ${e.message}"
            eventSink.append(stashFailedEvent(input.name, "unstash", reason))
            return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
        }
        if (Files.isSymbolicLink(stashRoot)) {
            val reason = "unstash stashRoot '$stashRoot' is a symlink (resolves to '$stashRootReal'); refusing to follow"
            eventSink.append(stashFailedEvent(input.name, "unstash", reason))
            return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
        }
        val workspaceRootReal: Path = try {
            workspaceRoot.toRealPath()
        } catch (e: IOException) {
            val reason = "unstash workspace root cannot be resolved to a real path: ${e.message}"
            eventSink.append(stashFailedEvent(input.name, "unstash", reason))
            return StashFailed(failureKind = FailureKind.INFRASTRUCTURE, message = reason)
        }

        val restoreRoot = if (input.into.isNullOrBlank()) {
            workspaceRoot
        } else {
            val target = workspaceRoot.resolve(input.into).normalize()
            // Defense-in-depth: the typed contract already forbids ".." segments and
            // absolute paths, but `normalize()` does NOT follow symlinks. We resolve
            // the real path to detect symlink-based escapes of the workspace.
            val targetReal: Path = try {
                target.toRealPath()
            } catch (e: IOException) {
                val reason = "unstash '${input.name}' 'into' path '$target' cannot be resolved (broken symlink?): ${e.message}"
                eventSink.append(stashFailedEvent(input.name, "unstash", reason))
                return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
            }
            if (!targetReal.startsWith(workspaceRootReal)) {
                val reason = "unstash '${input.name}' 'into' path '$target' (real '$targetReal') escapes workspace (real '$workspaceRootReal'); refusing to follow symlinks"
                eventSink.append(stashFailedEvent(input.name, "unstash", reason))
                return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
            }
            // Also reject if `target` is itself a symlink (defence-in-depth: even
            // when the symlink resolves inside the workspace, an in-workspace
            // symlink could cause restored files to land in an unintended tree).
            if (Files.isSymbolicLink(target)) {
                val reason = "unstash '${input.name}' 'into' path '$target' is a symlink (resolves to '$targetReal'); refusing to follow symlinks"
                eventSink.append(stashFailedEvent(input.name, "unstash", reason))
                return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
            }
            Files.createDirectories(target)
            target
        }
        // Resolve restoreRoot's real path for the per-file containment check.
        val restoreRootReal: Path = try {
            restoreRoot.toRealPath()
        } catch (e: IOException) {
            restoreRoot.toAbsolutePath().normalize()
        }
        // Also reject if restoreRoot is a symlink (defence-in-depth).
        if (Files.isSymbolicLink(restoreRoot)) {
            val reason = "unstash restoreRoot '$restoreRoot' is a symlink (resolves to '$restoreRootReal'); refusing to follow symlinks"
            eventSink.append(stashFailedEvent(input.name, "unstash", reason))
            return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
        }

        return try {
            val entries = mutableListOf<RestoredEntry>()
            // WU-RP-012 — symlink filter on the source. Files.walk with the
            // JVM default FOLLOW_LINKS=true would expose files from a
            // symlink-to-file (which `Files.isRegularFile` returns true for
            // because it follows the link). Refuse any regular file that is
            // actually a symlink, with a typed SCRIPT-level rejection (not
            // INFRASTRUCTURE — symlinks in the archive are a script-author
            // decision, not a runtime crash).
            //
            // Materialise the walk first (the walk follows links; filtering
            // afterwards is safe because each returned Path is the entry as
            // found, not its target). Then iterate with normal `break` so
            // we can short-circuit on the first rejection cleanly.
            val candidates: List<Path> = Files.walk(stashRoot).use { stream ->
                stream.filter(Files::isRegularFile).toList()
            }
            for (src in candidates) {
                if (Files.isSymbolicLink(src)) {
                    val reason = "stashRoot contains a symlink at '$src'; refusing to restore"
                    eventSink.append(stashFailedEvent(input.name, "unstash", reason))
                    return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
                }
                val rel = stashRoot.relativize(src)
                val dst = restoreRoot.resolve(rel.toString())
                // Defence-in-depth: every dst MUST resolve inside restoreRootReal.
                val dstReal = try {
                    dst.toRealPath()
                } catch (e: IOException) {
                    dst.toAbsolutePath().normalize()
                }
                if (!dstReal.startsWith(restoreRootReal)) {
                    val reason = "restored path '$dst' (real '$dstReal') escapes restoreRoot (real '$restoreRootReal')"
                    eventSink.append(stashFailedEvent(input.name, "unstash", reason))
                    return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
                }
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
