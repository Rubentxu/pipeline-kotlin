package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.runtime.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RestoredEntry
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.StashFailed as StashFailedEvent
import dev.rubentxu.pipeline.v2.events.StashRestored
import dev.rubentxu.pipeline.v2.events.StashedEntry
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Comparator
import java.util.UUID

/**
 * The typed refusal of a stash capture the generation cannot honour (a stored node that
 * resolves outside the generation or is not a regular file, bytes that do not match their
 * source).
 *
 * It remains an [IllegalStateException] on purpose: [StashOperationsAdapter.stash] catches
 * every exception at its boundary, deletes the staged generation and re-emits the refusal
 * as the typed [StashFailed] result, so naming the failure changes what a reader of the
 * throw site sees without changing what the handler sees.
 */
internal class StashCaptureRefusal(message: String) : IllegalStateException(message)

/**
 * Production adapter for [StashOperations] (the durable stash tier work / Tier B #1).
 *
 * Storage layout: `<controlDirRoot>/stashes/<runId>/<name>/`, a directory owning one
 * immutable generation per published stash (`gen-<N>/MANIFEST.json` +
 * `gen-<N>/snapshot/...`) and an atomically replaced `CURRENT` pointer. The stash
 * directory is a SIBLING of `<controlDirRoot>/workspace/...` and
 * `<controlDirRoot>/artefacts/...`, so `WorkspaceResolver.cleanupAfterComplete()` does
 * NOT destroy stashes when the producing stage finishes (the workspace-cleanup integrity
 * preserved).
 *
 * The capture reuses the verified port 339 introduced for the archive tier — every
 * [CaptureReceipt] states a digest and a size read back from the stored target — but the
 * stash stays the run's temporary tier: nothing is published to a durable artifact index
 * and no `ArtifactArchived` event is emitted. Overwrite by name is an explicit new
 * generation, so a reader that resolved the pointer mid-flight keeps reading the complete
 * snapshot it named (retention and GC of superseded generations belong to the store
 * lifecycle, not to this adapter).
 *
 * The adapter is the ONLY place that:
 * - reads/writes the stash directory on disk,
 * - computes per-file sha256 / size,
 * - writes the generation manifest and moves the `CURRENT` reference,
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
    /** the project-workspace overlay parity: optional project-workspace override (--workspace). */
    private val workspaceBase: Path? = null,
    /**
     * the shared execution location.
     *
     * `stash` and `unstash` operate on the current directory (anchor matrix),
     * so they must read the same location every other workspace-aware Step
     * reads instead of re-deriving `controlDirRoot/stageName/index` themselves
     * — the reconstruction the domain resolver exists to eliminate (the shared-execution-location invariant).
     * `null` keeps direct construction working; the bridge always supplies it.
     */
    private val executionLocation: dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation? = null,
) : StashOperations {

    /**
     * The directory a stash is taken from and restored into.
     *
     * A caller-chosen `dir(...)` scope moves it; with no scope it is the stage
     * workspace the location reports, which is what the old resolver returned.
     * The stash *store* stays under `controlDirRoot/stashes/...` and is never a
     * workspace anchor (INTERNAL_STORE in the anchor matrix).
     */
    private fun sourceRoot(): Path {
        val shared = executionLocation?.cwd
        if (shared != null) {
            Files.createDirectories(shared)
            return shared
        }
        val resolver = WorkspaceResolver(controlDirRoot, workspaceBase)
        return resolver.ensureCreated(resolver.resolve(stageIdentity.name, stageIdentity.index))
    }

    /**
     * The real path of a location that may not exist yet.
     *
     * A restore judges a destination BEFORE the copy creates it, so the whole
     * path cannot be resolved: `toRealPath` fails on a path that is absent.
     * The deepest existing ancestor is resolved instead, and the segments that
     * are still missing are re-attached to it.
     *
     * Both sides of a containment check must then speak the same coordinate
     * system, which is the one the filesystem uses. Mixing the two is what
     * made an honest restore fail: the root was compared resolved and the
     * destination unresolved, so a workspace reached through a symbolic link
     * looked like an escape. It is also what let an escape pass: a destination
     * below a link that leaves the workspace resolved to the link's target
     * while the root did not, so the two never matched.
     *
     * A symbolic link is treated as an existing node, not as a transparent
     * one: the ancestor is resolved THROUGH it, which is what makes a link
     * that leaves the workspace visible as an escape.
     *
     * A node that exists but cannot be resolved — a broken link — is treated as
     * a name and the walk continues upwards. Falling back to the unresolved path
     * would put the two sides of a containment check back into different
     * coordinate systems, which is the mistake this function exists to remove.
     */
    private fun realPathOf(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        val missing = ArrayDeque<Path>()
        var existing = absolute
        while (true) {
            if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                val resolved = try {
                    existing.toRealPath()
                } catch (e: IOException) {
                    null
                }
                if (resolved != null) {
                    // The type is written out because a `var` keeps the nullable type of
                    // its initialiser even when that initialiser is already narrowed.
                    var out: Path = resolved
                    for (segment in missing) {
                        out = out.resolve(segment.toString())
                    }
                    return out
                }
            }
            val name = existing.fileName ?: return absolute
            val parent = existing.parent ?: return absolute
            missing.addFirst(name)
            existing = parent
        }
    }

    override fun stash(input: StashInput): StashResult {
        stashRefusal(input.name, "stash")?.let { return it }
        val workspaceRoot = sourceRoot()

        // paths confinement for stash.
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
            // Both glob fields of [StashInput] are comma-separated LISTS carried in a [String].
            // The split lives in [AntStyleGlob.splitPatternList], next to the dialect it belongs
            // to, so every caller of a list-in-a-String field reads it the same way: a comma is a
            // separator only at the top level, never inside `{...}` or `[...]`.
            //
            // This adapter used to carry its own copy of that split, and it shredded Ant
            // alternation: `build/**/*.{jar,war}` became `build/**/*.{jar` plus `war}`, both of
            // which match nothing, and the stash then reported "matched no files".
            //
            // Consequence, stated rather than hidden: a `stash` of a file whose name contains a
            // bare comma is still not expressible through this field. A comma inside a brace
            // group is expressible, and now survives.
            AntStyleGlob.fromList(AntStyleGlob.splitPatternList(input.includes)).match(
                root = workspaceRoot,
                excludes = AntStyleGlob.splitPatternList(input.excludes),
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

        // symlink filter. After AntStyleGlob.match (which uses
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
        val stashRootReal: Path = realPathOf(stashRoot)
        var stagedGen: Path? = null
        return try {
            // Overwrite by name = a new explicit generation, never a delete-in-place:
            // a reader that resolved `CURRENT` earlier keeps reading the complete tree
            // it named, because nothing here removes or rewrites a published generation.
            if (Files.exists(stashRoot) && Files.isSymbolicLink(stashRoot)) {
                val reason = "stash store directory '$stashRoot' is a symlink (resolves to '$stashRootReal'); refusing to write through it"
                eventSink.append(stashFailedEvent(input.name, "stash", reason))
                return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
            }
            Files.createDirectories(stashRoot)

            // The generation number is CLAIMED, not chosen: `createDirectory` fails when the
            // name already exists, so two stashes racing on the same number cannot both write
            // inside it — the loser takes the next number. nextGeneration alone only reads a
            // stale-consistent view; the exclusive create is what makes "nothing here removes
            // or rewrites a published generation" hold under the engine's parallel branches.
            var generation = nextGeneration(stashRoot)
            var genDir = stashRoot.resolve(StashStoreLayout.generationDir(generation))
            while (true) {
                try {
                    Files.createDirectory(genDir)
                    break
                } catch (taken: FileAlreadyExistsException) {
                    generation += 1
                    genDir = stashRoot.resolve(StashStoreLayout.generationDir(generation))
                }
            }
            stagedGen = genDir
            val genDirReal = realPathOf(genDir)
            val snapshotDir = genDir.resolve(StashStoreLayout.SNAPSHOT_DIR)
            Files.createDirectories(snapshotDir)

            val receipts = mutableListOf<CaptureReceipt>()
            for (file in matched) {
                val rel = workspaceRoot.relativize(file)
                val target = snapshotDir.resolve(rel.toString())
                // Defence-in-depth: target MUST stay inside the generation. Even
                // though `rel` is derived from `workspaceRootReal`-bound files,
                // a poisoned glob or an unusual lexical layout could in principle
                // produce a rel path that resolves outside. Reject fail-closed.
                val targetReal = realPathOf(target)
                if (!targetReal.startsWith(genDirReal)) {
                    val reason = "stash target '$target' (real '$targetReal') escapes the generation (real '$genDirReal'); refusing"
                    runCatching { deleteTree(genDir) }
                    eventSink.append(stashFailedEvent(input.name, "stash", reason))
                    return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
                }
                Files.createDirectories(target.parent ?: snapshotDir)
                receipts.add(captureStashFile(file, target, rel.toString(), genDirReal))
            }

            // The capture is complete: the manifest first, then the reference moves to
            // the generation. A reader can only ever resolve a generation whose whole
            // tree and manifest are already on disk.
            StashManifest(name = input.name, generation = generation, rows = receipts.toList())
                .writeTo(genDir)
            publishCurrentPointer(stashRoot, StashStoreLayout.generationDir(generation))

            val entries = receipts.map { StashedEntry(relPath = it.name.value, sha256 = it.blobId.value, sizeBytes = it.size) }
            eventSink.append(
                StashCreated(
                    eventId = UUID.randomUUID().toString(),
                    runId = runIdString,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stageName = stageIdentity.name,
                    name = input.name,
                    files = entries,
                ),
            )
            StashSuccess(entries = entries)
        } catch (e: Exception) {
            // The unfinished generation is unreferenced — delete it so the store keeps
            // only complete snapshots plus the pointer. The previous reference stays.
            stagedGen?.let { runCatching { deleteTree(it) } }
            val reason = "stash '${input.name}' failed: ${e.message}"
            eventSink.append(stashFailedEvent(input.name, "stash", reason))
            StashFailed(failureKind = FailureKind.INFRASTRUCTURE, message = reason)
        }
    }

    override fun unstash(input: UnstashInput): StashResult {
        stashRefusal(input.name, "unstash")?.let { return it }
        val workspaceRoot = sourceRoot()

        val stashRoot = stashRoot(runIdString, input.name)
        if (!Files.exists(stashRoot)) {
            val reason = "unstash '${input.name}' failed: no such stash in run $runIdString"
            eventSink.append(stashFailedEvent(input.name, "unstash", reason))
            return StashFailed(failureKind = FailureKind.USER, message = reason)
        }

        // paths confinement for unstash.
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
        val restoreRootReal: Path = realPathOf(restoreRoot)
        // Also reject if restoreRoot is a symlink (defence-in-depth).
        if (Files.isSymbolicLink(restoreRoot)) {
            val reason = "unstash restoreRoot '$restoreRoot' is a symlink (resolves to '$restoreRootReal'); refusing to follow symlinks"
            eventSink.append(stashFailedEvent(input.name, "unstash", reason))
            return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
        }

        return try {
            // Resolve the reference: a published name points at exactly one complete
            // generation snapshot; a store without a pointer is the legacy flat layout
            // (a hand-shaped archive), read directly as before — local-first absence of
            // the generation layout never breaks a restore.
            val contentRoot: Path = when (val resolved = resolveCurrentGeneration(stashRoot, stashRootReal)) {
                is GenerationResolution.Published -> resolved.dir
                is GenerationResolution.Corrupt -> {
                    val reason = "unstash '${input.name}' failed: ${resolved.reason}"
                    eventSink.append(stashFailedEvent(input.name, "unstash", reason))
                    return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
                }
                GenerationResolution.Legacy -> stashRoot
            }

            val entries = mutableListOf<RestoredEntry>()
            // symlink filter on the source. Files.walk with the
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
            val candidates: List<Path> = Files.walk(contentRoot).use { stream ->
                stream.filter(Files::isRegularFile).toList()
            }
            for (src in candidates) {
                if (Files.isSymbolicLink(src)) {
                    val reason = "stashRoot contains a symlink at '$src'; refusing to restore"
                    eventSink.append(stashFailedEvent(input.name, "unstash", reason))
                    return StashFailed(failureKind = FailureKind.SCRIPT, message = reason)
                }
                val rel = contentRoot.relativize(src)
                val dst = restoreRoot.resolve(rel.toString())
                // Defence-in-depth: every dst MUST resolve inside restoreRootReal.
                val dstReal = realPathOf(dst)
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

    /**
     * The next generation number a name gets: one past the greatest of the published
     * reference and the generation directories still on disk. Reading the reference is
     * best-effort here — a pointer that names no generation is refused at [unstash],
     * not silently renumbered around — because a restash must never reuse a directory
     * a reader may still hold.
     */
    private fun nextGeneration(nameDir: Path): Int {
        var max = 0
        val pointer = nameDir.resolve(StashStoreLayout.CURRENT_POINTER)
        if (Files.isRegularFile(pointer)) {
            max = maxOf(max, StashStoreLayout.pointerGeneration(Files.readString(pointer)) ?: 0)
        }
        if (Files.isDirectory(nameDir)) {
            Files.list(nameDir).use { stream ->
                stream.forEach { child ->
                    val number = child.fileName.toString().removePrefix(StashStoreLayout.GENERATION_PREFIX)
                    if (Files.isDirectory(child) && number.toIntOrNull()?.let { it > 0 } == true) {
                        max = maxOf(max, number.toInt())
                    }
                }
            }
        }
        return max + 1
    }

    /**
     * Moves the reference to [generationDirName] atomically: the pointer is written to a
     * sibling temp file and renamed over `CURRENT`, so a reader either sees the previous
     * generation or the new one — never a half-written pointer. The rename is the publish
     * point of a capture, which is why callers may only do it with a complete,
     * manifest-carrying generation.
     */
    private fun publishCurrentPointer(nameDir: Path, generationDirName: String) {
        val pointer = nameDir.resolve(StashStoreLayout.CURRENT_POINTER)
        val temp = Files.createTempFile(nameDir, "${StashStoreLayout.CURRENT_POINTER}.", ".tmp")
        try {
            Files.writeString(temp, generationDirName)
            Files.move(temp, pointer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    /**
     * Captures one file into the staged generation and returns the receipt of what the
     * store holds afterwards — the same law the archive port of 339 states, applied to
     * the run's temporary tier: the digest and the size are read back FROM the stored
     * target, and a capture whose bytes do not match the source (or would land outside
     * the generation through a planted link) is an exception that fails the whole stash
     * before any reference moves. There is no idempotent-skip branch here: a generation
     * is fresh by construction, so every position is empty when the capture wants it.
     */
    private fun captureStashFile(source: Path, target: Path, relativePath: String, genDirReal: Path): CaptureReceipt {
        val expectedSha = Sha256.ofFile(source)
        val expectedSize = Files.size(source)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        // Re-read the facts the receipt will state, and refuse the capture when the store
        // does not carry what the source promised. The containment walk repeats too: a
        // link planted between the copy and this read would otherwise leave the receipt
        // describing bytes that are no longer under the generation root.
        if (!target.toRealPath().startsWith(genDirReal)) {
            throw StashCaptureRefusal("stored capture for '$relativePath' resolves outside the generation")
        }
        val stored = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!stored.isRegularFile) {
            throw StashCaptureRefusal("stored capture for '$relativePath' is not a regular file")
        }
        val capturedSha = Sha256.ofFile(target)
        val capturedSize = Files.size(target)
        if (capturedSha != expectedSha || capturedSize != expectedSize) {
            throw StashCaptureRefusal("captured bytes for '$relativePath' do not match the source after the copy")
        }
        return CaptureReceipt(
            captureId = ArtifactId.mint(),
            runId = runIdString,
            stageName = stageIdentity.name,
            name = ArtifactName(relativePath),
            blobId = BlobId(capturedSha),
            size = capturedSize,
        )
    }

    /** What resolving a name's reference finds in the store. */
    private sealed interface GenerationResolution {
        /** A pointer names a complete generation; [dir] is its snapshot tree. */
        data class Published(val dir: Path) : GenerationResolution

        /** The store is corrupt where the reference or the generation it names is concerned. */
        data class Corrupt(val reason: String) : GenerationResolution

        /** No pointer exists and the store holds no generation-shaped directory: the legacy flat layout, read directly. */
        data object Legacy : GenerationResolution
    }

    /**
     * Resolves the tree an unstash must read: the snapshot of the generation the pointer
     * names, the legacy flat directory when no pointer was ever published, or a typed
     * refusal when a published reference does not lead to a complete generation. The
     * manifest is read as part of resolution — it is the audit record that makes the
     * generation trustworthy, and an unreadable one means the reference cannot be served.
     */
    private fun resolveCurrentGeneration(stashRoot: Path, stashRootReal: Path): GenerationResolution {
        val pointer = stashRoot.resolve(StashStoreLayout.CURRENT_POINTER)
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            // A store holding generation-shaped directories but no pointer was never
            // published: an abnormal death mid-capture leaves exactly that. Its tree is
            // truncated content under `gen-N/snapshot/...` for a name no reader ever saw,
            // so it is refused as corrupt — the flat legacy walk would present the internal
            // generation paths as restored content.
            if (hasGenerationDir(stashRoot)) {
                return GenerationResolution.Corrupt(
                    "the store carries generation directories but no published reference",
                )
            }
            return GenerationResolution.Legacy
        }
        if (Files.isSymbolicLink(pointer)) {
            return GenerationResolution.Corrupt("the stash reference is a symlink; refusing to follow it")
        }
        val generationDirName = Files.readString(pointer).trim()
        if (!StashStoreLayout.isGenerationDir(generationDirName)) {
            return GenerationResolution.Corrupt("the stash reference names '$generationDirName', not a generation directory")
        }
        val genDir = stashRoot.resolve(generationDirName)
        if (!Files.isDirectory(genDir) || Files.isSymbolicLink(genDir)) {
            return GenerationResolution.Corrupt("the referenced generation '$generationDirName' is absent from the store")
        }
        if (!genDir.toRealPath().startsWith(stashRootReal)) {
            return GenerationResolution.Corrupt("the referenced generation '$generationDirName' resolves outside the stash store")
        }
        val manifest = try {
            StashManifest.readFrom(genDir)
        } catch (e: Exception) {
            return GenerationResolution.Corrupt("the manifest of generation '$generationDirName' is unreadable: ${e.message}")
        }
        if (manifest.generation != StashStoreLayout.pointerGeneration(generationDirName)) {
            return GenerationResolution.Corrupt("the manifest of generation '$generationDirName' states generation ${manifest.generation}")
        }
        val snapshot = genDir.resolve(StashStoreLayout.SNAPSHOT_DIR)
        if (!Files.isDirectory(snapshot) || Files.isSymbolicLink(snapshot)) {
            return GenerationResolution.Corrupt("generation '$generationDirName' carries no snapshot tree")
        }
        return GenerationResolution.Published(snapshot)
    }

    /** True when the name directory holds at least one generation-shaped subdirectory. */
    private fun hasGenerationDir(nameDir: Path): Boolean =
        Files.list(nameDir).use { stream ->
            stream.anyMatch { child ->
                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) &&
                    StashStoreLayout.isGenerationDir(child.fileName.toString())
            }
        }

    /** Removes a staged (never published) generation tree, deepest node first. */
    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * The typed refusal when this run identity or this stash name cannot name its
     * stash directory.
     *
     * The stash directory is named after the run and the stash, so both parts of the
     * path have to be able to name it. An identity that cannot is refused here, at the
     * border of the operation and with the reason stated, instead of being folded onto
     * another run's directory. A name that is a traversal (`.`, `..`) would move the
     * store outside the run's own directory — the same refusal applies to it, before
     * any byte of the store is created.
     */
    private fun stashRefusal(name: String, operation: String): StashResult? {
        unnameableRunIdReason(runIdString, operation)?.let { reason ->
            eventSink.append(stashFailedEvent(name, operation, reason))
            return StashFailed(failureKind = FailureKind.USER, message = reason)
        }
        if (name == "." || name == "..") {
            val reason = "stash name '$name' cannot name a directory; refusing to $operation"
            eventSink.append(stashFailedEvent(name, operation, reason))
            return StashFailed(failureKind = FailureKind.USER, message = reason)
        }
        return null
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

    private fun sha256Of(file: Path): String {
        // Shared utility (B0): the same streaming loop with the same 8192-byte buffer, so the
        // digest value is unchanged for every entry already stashed.
        return Sha256.ofFile(file)
    }
}
