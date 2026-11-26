package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.step.artifact.CollisionPolicy
import dev.rubentxu.pipeline.v2.domain.step.artifact.ExecutablePermissionPolicy
import dev.rubentxu.pipeline.v2.domain.step.artifact.MaterializationError
import dev.rubentxu.pipeline.v2.domain.step.artifact.MaterializationOutcome
import dev.rubentxu.pipeline.v2.domain.step.artifact.MaterializationPlan
import dev.rubentxu.pipeline.v2.domain.step.artifact.MaterializationPolicy
import dev.rubentxu.pipeline.v2.domain.step.artifact.MaterializationRequest
import dev.rubentxu.pipeline.v2.domain.step.artifact.MaterializedFile
import dev.rubentxu.pipeline.v2.domain.step.artifact.PlannedMaterializationEntry
import dev.rubentxu.pipeline.v2.domain.step.artifact.WorkspaceFileMaterializer
import dev.rubentxu.pipeline.v2.domain.step.artifact.WorkspaceMaterializationPlanner
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Effectful adapter implementing [WorkspaceFileMaterializer] on the local filesystem.
 *
 * Enforces:
 * - pure path, traversal, permission, and duplicate validation via [WorkspaceMaterializationPlanner];
 * - symlink and hardlink rejection on source, destination, and destination parent directories;
 * - real-path workspace confinement via [WorkspacePathResolver.rejectSymlinkEscape];
 * - atomic staging in temporary files with size and SHA-256 verification before commit;
 * - rollback of all committed/backed-up destinations and cleanup of staging files on failure;
 * - explicit `0755` POSIX permissions only when authorized by both descriptor and policy.
 */
class LocalWorkspaceFileMaterializer internal constructor(
    private val stagingCopier: StagingCopier,
    private val fileMover: FileMover,
) : WorkspaceFileMaterializer {

    constructor() : this(
        stagingCopier = StagingCopier(::defaultCopyToStaging),
        fileMover = FileMover(::defaultMoveFile),
    )

    internal fun interface StagingCopier {
        fun copy(source: Path, stagingFile: Path)
    }

    internal fun interface FileMover {
        fun move(source: Path, destination: Path)
    }

    override fun materialize(request: MaterializationRequest): MaterializationOutcome {
        val entries = when (val plan = WorkspaceMaterializationPlanner.plan(request)) {
            is MaterializationPlan.Rejected -> return MaterializationOutcome.Failed(plan.error)
            is MaterializationPlan.Planned -> plan.entries
        }
        if (entries.isEmpty()) {
            return MaterializationOutcome.Materialized(emptyList())
        }

        val workspaceRoot = request.location.workspace.root.normalize()
        val workspaceRootReal = resolveWorkspaceRoot(request.location)
            ?: return MaterializationOutcome.Failed(
                MaterializationError.SymlinkRejected(workspaceRoot, "workspace-root"),
            )

        val preflightError = validateAllFilesystemPreconditions(
            location = request.location,
            workspaceRootReal = workspaceRootReal,
            entries = entries,
            policy = request.policy,
        )
        if (preflightError != null) {
            return MaterializationOutcome.Failed(preflightError)
        }

        return stageVerifyAndCommit(entries, request.policy)
    }

    private fun resolveWorkspaceRoot(location: ExecutionLocation): Path? {
        val root = location.workspace.root.normalize()
        if (Files.isSymbolicLink(root)) {
            return null
        }
        Files.createDirectories(root)
        val rootReal = realPathOf(root)
        if (Files.isSymbolicLink(location.cwd)) {
            return null
        }
        val cwdReal = realPathOf(location.cwd)
        if (WorkspacePathResolver.rejectSymlinkEscape(cwdReal, rootReal) is PathResolution.Rejected) {
            return null
        }
        return rootReal
    }

    private fun validateAllFilesystemPreconditions(
        location: ExecutionLocation,
        workspaceRootReal: Path,
        entries: List<PlannedMaterializationEntry>,
        policy: MaterializationPolicy,
    ): MaterializationError? {
        val root = location.workspace.root.normalize()
        val base = WorkspacePathResolver.baseFor(location, policy.anchor).normalize()
        val baseCheck = validateAncestorChain(root, base)
        if (baseCheck != null) return baseCheck

        for (entry in entries) {
            val sourceError = validateSource(entry.descriptor.sourcePath)
            if (sourceError != null) return sourceError

            val destError = validateDestination(
                workspaceRoot = root,
                workspaceRootReal = workspaceRootReal,
                destination = entry.resolvedDestination,
                policy = policy,
            )
            if (destError != null) return destError
        }
        return null
    }

    private fun validateSource(source: Path): MaterializationError? {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return MaterializationError.InvalidSource(source, "source file does not exist")
        }
        if (Files.isSymbolicLink(source)) {
            return MaterializationError.SymlinkRejected(source, "source")
        }
        val parent = source.parent
        if (parent != null && Files.isSymbolicLink(parent)) {
            return MaterializationError.SymlinkRejected(parent, "source-parent")
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return MaterializationError.InvalidSource(source, "source is not a regular file")
        }
        val links = linkCountOf(source)
        if (links != null && links > 1) {
            return MaterializationError.HardlinkRejected(source, links, "source")
        }
        return null
    }

    private fun validateDestination(
        workspaceRoot: Path,
        workspaceRootReal: Path,
        destination: Path,
        policy: MaterializationPolicy,
    ): MaterializationError? {
        val parent = destination.parent ?: workspaceRoot
        val ancestorError = validateAncestorChain(workspaceRoot, parent)
        if (ancestorError != null) return ancestorError

        val destReal = realPathOf(destination)
        if (WorkspacePathResolver.rejectSymlinkEscape(destReal, workspaceRootReal) is PathResolution.Rejected) {
            return MaterializationError.SymlinkRejected(destination, "destination-escape")
        }

        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        if (Files.isSymbolicLink(destination)) {
            return MaterializationError.SymlinkRejected(destination, "destination")
        }
        if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            return MaterializationError.DestinationIsDirectory(destination)
        }
        val links = linkCountOf(destination)
        if (links != null && links > 1) {
            return MaterializationError.HardlinkRejected(destination, links, "destination")
        }
        if (policy.collisionPolicy == CollisionPolicy.REJECT) {
            return MaterializationError.DestinationCollision(destination)
        }
        return null
    }

    private fun validateAncestorChain(workspaceRoot: Path, targetDir: Path): MaterializationError? {
        val normRoot = workspaceRoot.normalize()
        val normTarget = targetDir.normalize()
        if (!normTarget.startsWith(normRoot)) {
            return MaterializationError.SymlinkRejected(targetDir, "destination-parent")
        }
        val rel = normRoot.relativize(normTarget)
        var current = normRoot
        for (segment in rel) {
            if (segment.toString().isEmpty()) continue
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                return MaterializationError.SymlinkRejected(current, "destination-parent")
            }
        }
        return null
    }

    private fun stageVerifyAndCommit(
        entries: List<PlannedMaterializationEntry>,
        policy: MaterializationPolicy,
    ): MaterializationOutcome {
        val createdDirs = mutableListOf<Path>()
        val staged = mutableListOf<StagedEntry>()
        try {
            for (entry in entries) {
                val stagedEntry = stageAndVerifyEntry(entry, policy, createdDirs)
                when (stagedEntry) {
                    is StageResult.Failure -> {
                        cleanupStagingAndDirs(staged, createdDirs)
                        return MaterializationOutcome.Failed(stagedEntry.error)
                    }
                    is StageResult.Success -> staged.add(stagedEntry.entry)
                }
            }
            return commitWithRollback(staged, createdDirs)
        } catch (e: IOException) {
            cleanupStagingAndDirs(staged, createdDirs)
            return MaterializationOutcome.Failed(
                MaterializationError.IoFailure(null, e.message ?: "I/O failure during staging"),
            )
        }
    }

    private fun stageAndVerifyEntry(
        entry: PlannedMaterializationEntry,
        policy: MaterializationPolicy,
        createdDirs: MutableList<Path>,
    ): StageResult {
        val dest = entry.resolvedDestination
        val parent = dest.parent
        createDirectoriesTrackingNew(parent, createdDirs)
        val tempFile = Files.createTempFile(parent, ".pipelinek-stage-", ".tmp")
        return try {
            stagingCopier.copy(entry.descriptor.sourcePath, tempFile)
            val actualSize = Files.size(tempFile)
            if (actualSize != entry.descriptor.expectedSizeBytes) {
                Files.deleteIfExists(tempFile)
                return StageResult.Failure(
                    MaterializationError.SizeMismatch(
                        source = entry.descriptor.sourcePath,
                        expectedBytes = entry.descriptor.expectedSizeBytes,
                        actualBytes = actualSize,
                    ),
                )
            }
            val actualSha = Sha256.ofFile(tempFile)
            if (actualSha != entry.descriptor.expectedSha256) {
                Files.deleteIfExists(tempFile)
                return StageResult.Failure(
                    MaterializationError.ChecksumMismatch(
                        source = entry.descriptor.sourcePath,
                        expectedSha256 = entry.descriptor.expectedSha256,
                        actualSha256 = actualSha,
                    ),
                )
            }
            val applyExec = entry.descriptor.executable &&
                policy.executablePolicy == ExecutablePermissionPolicy.ALLOW_AUTHORIZED
            applyPosixPermissions(tempFile, applyExec)
            StageResult.Success(
                StagedEntry(
                    planned = entry,
                    stagingFile = tempFile,
                    executableApplied = applyExec,
                ),
            )
        } catch (e: IOException) {
            Files.deleteIfExists(tempFile)
            StageResult.Failure(
                MaterializationError.IoFailure(
                    dest,
                    e.message ?: "I/O failure while staging '${entry.descriptor.relativeDestination}'",
                ),
            )
        }
    }

    private fun commitWithRollback(
        staged: List<StagedEntry>,
        createdDirs: List<Path>,
    ): MaterializationOutcome {
        val backups = mutableListOf<BackupEntry>()
        val committedNew = mutableListOf<Path>()
        try {
            for (item in staged) {
                val dest = item.planned.resolvedDestination
                if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                    val backup = Files.createTempFile(dest.parent, ".pipelinek-bak-", ".tmp")
                    try {
                        fileMover.move(dest, backup)
                    } catch (e: IOException) {
                        runCatching { Files.deleteIfExists(backup) }
                        throw e
                    }
                    backups.add(BackupEntry(original = dest, backup = backup))
                    fileMover.move(item.stagingFile, dest)
                } else {
                    fileMover.move(item.stagingFile, dest)
                    committedNew.add(dest)
                }
            }
        } catch (e: IOException) {
            rollbackCommit(committedNew, backups)
            cleanupStagingAndDirs(staged, createdDirs)
            return MaterializationOutcome.Failed(
                MaterializationError.IoFailure(null, e.message ?: "I/O failure during commit"),
            )
        }
        backups.forEach { runCatching { Files.deleteIfExists(it.backup) } }
        val result = staged.map { item ->
            MaterializedFile(
                relativeDestination = item.planned.descriptor.relativeDestination,
                resolvedPath = item.planned.resolvedDestination,
                sizeBytes = item.planned.descriptor.expectedSizeBytes,
                sha256 = item.planned.descriptor.expectedSha256,
                executable = item.executableApplied,
            )
        }
        return MaterializationOutcome.Materialized(result)
    }

    private fun rollbackCommit(committedNew: List<Path>, backups: List<BackupEntry>) {
        committedNew.asReversed().forEach { path ->
            runCatching { Files.deleteIfExists(path) }
        }
        backups.asReversed().forEach { entry ->
            runCatching {
                if (Files.exists(entry.backup, LinkOption.NOFOLLOW_LINKS)) {
                    defaultMoveFile(entry.backup, entry.original)
                }
            }
        }
    }

    private fun cleanupStagingAndDirs(staged: List<StagedEntry>, createdDirs: List<Path>) {
        staged.forEach { runCatching { Files.deleteIfExists(it.stagingFile) } }
        createdDirs.asReversed().forEach { dir ->
            runCatching {
                if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    Files.list(dir).use { entries ->
                        if (entries.findFirst().isEmpty) {
                            Files.deleteIfExists(dir)
                        }
                    }
                }
            }
        }
    }

    private fun createDirectoriesTrackingNew(dir: Path, createdDirs: MutableList<Path>) {
        val toCreate = ArrayDeque<Path>()
        var current: Path? = dir
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            toCreate.addFirst(current)
            current = current.parent
        }
        for (missing in toCreate) {
            Files.createDirectory(missing)
            createdDirs.add(missing)
        }
    }

    private fun applyPosixPermissions(file: Path, executable: Boolean) {
        val view = Files.getFileAttributeView(file, PosixFileAttributeView::class.java) ?: return
        val mode = if (executable) "rwxr-xr-x" else "rw-r--r--"
        view.setPermissions(PosixFilePermissions.fromString(mode))
    }

    private fun linkCountOf(path: Path): Int? = try {
        (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as? Number)?.toInt()
    } catch (_: Exception) {
        null
    }

    /**
     * Resolves the real path of a location that may not exist yet by walking up to
     * the deepest existing ancestor, resolving it through symlinks, and re-attaching
     * the remaining relative segments.
     */
    private fun realPathOf(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        val missing = ArrayDeque<Path>()
        var existing = absolute
        while (true) {
            if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                val resolved = try {
                    existing.toRealPath()
                } catch (_: IOException) {
                    null
                }
                if (resolved != null) {
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

    private data class StagedEntry(
        val planned: PlannedMaterializationEntry,
        val stagingFile: Path,
        val executableApplied: Boolean,
    )

    private data class BackupEntry(
        val original: Path,
        val backup: Path,
    )

    private sealed interface StageResult {
        data class Success(val entry: StagedEntry) : StageResult
        data class Failure(val error: MaterializationError) : StageResult
    }

    private companion object {
        private const val BUFFER_SIZE = 8192

        fun defaultCopyToStaging(source: Path, stagingFile: Path) {
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(stagingFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        }

        fun defaultMoveFile(source: Path, destination: Path) {
            try {
                Files.move(
                    source,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
