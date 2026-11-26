package dev.rubentxu.pipeline.v2.domain.step.artifact

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.PathAnchor
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathError
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import java.nio.file.Path

/**
 * Descriptor of one authorized local file to materialize into a workspace.
 *
 * Guarantees that [expectedSizeBytes] is non-negative and [expectedSha256]
 * is a 64-character lowercase SHA-256 hex digest as produced by [Sha256].
 * Path confinement of [relativeDestination] is not decided here; it is
 * evaluated against an [ExecutionLocation] by [WorkspaceMaterializationPlanner].
 *
 * Refuses negative [expectedSizeBytes] and malformed [expectedSha256] at
 * construction time with [IllegalArgumentException].
 *
 * @property sourcePath local path to the authorized source file bytes
 * @property relativeDestination workspace-relative destination path
 * @property expectedSizeBytes exact expected byte length (`>= 0`)
 * @property expectedSha256 expected lowercase 64-hex SHA-256 digest
 * @property executable whether the descriptor requests POSIX execute bits (`0755`);
 *   defaults to `false` and is applied only when policy explicitly allows it
 */
data class AuthorizedFileDescriptor(
    val sourcePath: Path,
    val relativeDestination: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val executable: Boolean = false,
) {
    init {
        require(expectedSizeBytes >= 0) {
            "expectedSizeBytes must be non-negative, got $expectedSizeBytes"
        }
        require(Sha256.isSha256Hex(expectedSha256)) {
            "expectedSha256 must be 64 lowercase hex characters (0-9a-f)"
        }
    }
}

/**
 * Converts an [ArchivedFileEntry] into an [AuthorizedFileDescriptor].
 *
 * Guarantees exact preservation of size and SHA-256 from the archived entry
 * and defaults [executable] to `false`. Refuses invalid size or digest via
 * [AuthorizedFileDescriptor] construction invariants.
 */
fun ArchivedFileEntry.toAuthorizedDescriptor(
    relativeDestination: String = relPath,
    executable: Boolean = false,
): AuthorizedFileDescriptor = AuthorizedFileDescriptor(
    sourcePath = Path.of(absolutePath),
    relativeDestination = relativeDestination,
    expectedSizeBytes = sizeBytes,
    expectedSha256 = sha256,
    executable = executable,
)

/**
 * Policy governing collisions with an existing regular file at the destination.
 *
 * Guarantees explicit handling of pre-existing destination files without
 * silent partial overwrites.
 */
enum class CollisionPolicy {
    /** Refuse materialization if any target file already exists, leaving prior files intact. */
    REJECT,

    /** Atomically replace an existing regular file, restoring it on batch failure. */
    OVERWRITE,
}

/**
 * Policy governing collisions when a destination path already exists as a directory.
 *
 * Guarantees that a regular-file materialization never silently deletes or
 * replaces a directory tree.
 */
enum class DirectoryCollisionPolicy {
    /** Refuse materialization whenever the target path is an existing directory. */
    REJECT,
}

/**
 * Policy governing POSIX execute permissions (`0755`) on materialized files.
 *
 * Guarantees that execute permissions are never granted by default.
 */
enum class ExecutablePermissionPolicy {
    /** Refuse any descriptor requesting `executable = true`; materialize regular files as `0644`. */
    FORBID,

    /** Apply `0755` only when the descriptor sets `executable = true`; otherwise apply `0644`. */
    ALLOW_AUTHORIZED,
}

/**
 * Complete policy for workspace file materialization.
 *
 * Defaults to fail-closed settings: reject file collisions, reject directory
 * collisions, forbid executable permissions, and resolve relative paths
 * against [PathAnchor.CURRENT_DIRECTORY] so `dir(...)` scopes are respected.
 *
 * @property collisionPolicy behavior when a destination regular file already exists
 * @property directoryPolicy behavior when a destination path is an existing directory
 * @property executablePolicy whether descriptor-requested execute bits are permitted
 * @property anchor base anchor inside [ExecutionLocation] for relative destinations
 */
data class MaterializationPolicy(
    val collisionPolicy: CollisionPolicy = CollisionPolicy.REJECT,
    val directoryPolicy: DirectoryCollisionPolicy = DirectoryCollisionPolicy.REJECT,
    val executablePolicy: ExecutablePermissionPolicy = ExecutablePermissionPolicy.FORBID,
    val anchor: PathAnchor = PathAnchor.CURRENT_DIRECTORY,
)

/**
 * Request to materialize a list of authorized files into [location].
 *
 * @property location target execution location (workspace root and effective `cwd`)
 * @property files ordered list of authorized file descriptors to materialize
 * @property policy confinement, collision, and permission policy
 */
data class MaterializationRequest(
    val location: ExecutionLocation,
    val files: List<AuthorizedFileDescriptor>,
    val policy: MaterializationPolicy = MaterializationPolicy(),
)

/**
 * Record of one file successfully materialized into the workspace.
 *
 * Guarantees that the file at [resolvedPath] was verified against [sizeBytes]
 * and [sha256] before being committed atomically to its destination.
 *
 * @property relativeDestination original relative destination from the descriptor
 * @property resolvedPath confined absolute path inside the workspace
 * @property sizeBytes verified byte length written to [resolvedPath]
 * @property sha256 verified SHA-256 hex digest of the written bytes
 * @property executable whether POSIX execute bits (`0755`) were applied
 */
data class MaterializedFile(
    val relativeDestination: String,
    val resolvedPath: Path,
    val sizeBytes: Long,
    val sha256: String,
    val executable: Boolean,
)

/**
 * Closed hierarchy of materialization refusals and failures.
 *
 * Every variant identifies the offending input or path so callers fail closed
 * without exposing partial files or mutating prior destinations.
 */
sealed interface MaterializationError {

    /**
     * Destination string is empty, blank, contains NUL bytes, or is absolute.
     */
    data class InvalidDestination(
        val destination: String,
        val reason: String,
    ) : MaterializationError

    /**
     * Destination contains `..` traversal or resolves outside the workspace root.
     */
    data class PathTraversalRejected(
        val destination: String,
        val error: WorkspacePathError,
    ) : MaterializationError

    /**
     * A symbolic link was detected in source, destination, or parent directories,
     * or the canonical real path escapes the workspace root.
     */
    data class SymlinkRejected(
        val path: Path,
        val role: String,
    ) : MaterializationError

    /**
     * A file with link count greater than 1 (hardlink) was detected in source or destination.
     */
    data class HardlinkRejected(
        val path: Path,
        val linkCount: Int,
        val role: String,
    ) : MaterializationError

    /**
     * Source file does not exist or is not a regular file.
     */
    data class InvalidSource(
        val source: Path,
        val reason: String,
    ) : MaterializationError

    /**
     * Observed byte count differs from [expectedBytes].
     */
    data class SizeMismatch(
        val source: Path,
        val expectedBytes: Long,
        val actualBytes: Long,
    ) : MaterializationError

    /**
     * Observed SHA-256 digest differs from [expectedSha256].
     */
    data class ChecksumMismatch(
        val source: Path,
        val expectedSha256: String,
        val actualSha256: String,
    ) : MaterializationError

    /**
     * Destination already exists as a regular file under [CollisionPolicy.REJECT].
     */
    data class DestinationCollision(val destination: Path) : MaterializationError

    /**
     * Destination path already exists as a directory.
     */
    data class DestinationIsDirectory(val destination: Path) : MaterializationError

    /**
     * Two descriptors in the same request resolve to the same destination path.
     */
    data class DuplicateDestination(
        val relativeDestination: String,
        val resolvedPath: Path,
    ) : MaterializationError

    /**
     * Descriptor requested `executable = true` under [ExecutablePermissionPolicy.FORBID].
     */
    data class ExecutableNotAuthorized(val relativeDestination: String) : MaterializationError

    /**
     * Filesystem I/O failed during staging, verification, or commit; prior destinations
     * were rolled back and temporary staging files were cleaned up.
     */
    data class IoFailure(
        val path: Path?,
        val reason: String,
    ) : MaterializationError
}

/**
 * Outcome of materializing a [MaterializationRequest].
 *
 * Either all files succeeded atomically ([Materialized]) or none were exposed
 * and any prior destinations remain intact ([Failed]).
 */
sealed interface MaterializationOutcome {

    /** All requested files were verified and committed atomically. */
    data class Materialized(val files: List<MaterializedFile>) : MaterializationOutcome

    /** Materialization failed closed with [error]; no partial files are exposed. */
    data class Failed(val error: MaterializationError) : MaterializationOutcome
}

/**
 * Port for effectful materialization of authorized files into a workspace.
 *
 * Implementations must validate confinement, reject symlinks and hardlinks,
 * verify byte size and SHA-256 before committing, apply permissions only when
 * authorized, and roll back on failure so prior destinations remain intact.
 */
interface WorkspaceFileMaterializer {

    /**
     * Materialize every file in [request] according to its policy.
     *
     * Never exposes partial files at any destination; returns [MaterializationOutcome.Failed]
     * with all prior destinations intact if any validation, integrity, or I/O check fails.
     */
    fun materialize(request: MaterializationRequest): MaterializationOutcome
}

/**
 * One validated entry ready for effectful staging and commit.
 *
 * @property descriptor original authorized file descriptor
 * @property resolvedDestination normalized absolute path inside the workspace
 */
data class PlannedMaterializationEntry(
    val descriptor: AuthorizedFileDescriptor,
    val resolvedDestination: Path,
)

/**
 * Result of pure pre-flight planning via [WorkspaceMaterializationPlanner].
 */
sealed interface MaterializationPlan {

    /** All descriptors passed pure path, permission, and uniqueness checks. */
    data class Planned(val entries: List<PlannedMaterializationEntry>) : MaterializationPlan

    /** At least one descriptor failed pure validation; no I/O should proceed. */
    data class Rejected(val error: MaterializationError) : MaterializationPlan
}

/**
 * Pure functional planner for workspace file materialization.
 *
 * Performs zero filesystem I/O. Validates that every destination is strictly
 * relative (no empty/blank strings, no NUL bytes, no leading `/` or `\`, no
 * absolute paths), contains no `..` traversal segments, resolves within
 * [ExecutionLocation] via [WorkspacePathResolver], respects
 * [ExecutablePermissionPolicy], and targets distinct destination paths.
 */
object WorkspaceMaterializationPlanner {

    /**
     * Validate and resolve all entries in [request] without touching the filesystem.
     *
     * Refuses invalid destinations, absolute paths, `..` traversal, workspace escapes,
     * unauthorized executable requests, and duplicate destination paths.
     */
    fun plan(request: MaterializationRequest): MaterializationPlan {
        val planned = ArrayList<PlannedMaterializationEntry>(request.files.size)
        val seenDestinations = HashSet<Path>(request.files.size)

        for (descriptor in request.files) {
            when (val entryPlan = planSingle(request.location, request.policy, descriptor)) {
                is SinglePlan.Rejected -> return MaterializationPlan.Rejected(entryPlan.error)
                is SinglePlan.Accepted -> {
                    if (!seenDestinations.add(entryPlan.entry.resolvedDestination)) {
                        return MaterializationPlan.Rejected(
                            MaterializationError.DuplicateDestination(
                                relativeDestination = descriptor.relativeDestination,
                                resolvedPath = entryPlan.entry.resolvedDestination,
                            ),
                        )
                    }
                    planned.add(entryPlan.entry)
                }
            }
        }
        return MaterializationPlan.Planned(planned)
    }

    private fun planSingle(
        location: ExecutionLocation,
        policy: MaterializationPolicy,
        descriptor: AuthorizedFileDescriptor,
    ): SinglePlan {
        val dest = descriptor.relativeDestination
        val formatError = validateDestinationSyntax(dest)
        if (formatError != null) {
            return SinglePlan.Rejected(formatError)
        }
        if (descriptor.executable && policy.executablePolicy == ExecutablePermissionPolicy.FORBID) {
            return SinglePlan.Rejected(MaterializationError.ExecutableNotAuthorized(dest))
        }
        val candidate = Path.of(dest)
        if (candidate.any { it.toString() == ".." }) {
            return SinglePlan.Rejected(
                MaterializationError.PathTraversalRejected(
                    destination = dest,
                    error = WorkspacePathError.EscapesWorkspace(dest, location.workspace.root),
                ),
            )
        }
        return when (val resolved = WorkspacePathResolver.resolve(location, policy.anchor, dest)) {
            is PathResolution.Rejected -> SinglePlan.Rejected(
                MaterializationError.PathTraversalRejected(dest, resolved.reason),
            )
            is PathResolution.Resolved -> SinglePlan.Accepted(
                PlannedMaterializationEntry(descriptor, resolved.path),
            )
        }
    }

    private fun validateDestinationSyntax(dest: String): MaterializationError? {
        if (dest.isBlank()) {
            return MaterializationError.InvalidDestination(dest, "destination must not be blank")
        }
        if ('\u0000' in dest) {
            return MaterializationError.InvalidDestination(dest, "destination must not contain NUL bytes")
        }
        if (dest.startsWith("/") || dest.startsWith("\\") || Path.of(dest).isAbsolute) {
            return MaterializationError.InvalidDestination(dest, "destination must be relative, not absolute")
        }
        return null
    }

    private sealed interface SinglePlan {
        data class Accepted(val entry: PlannedMaterializationEntry) : SinglePlan
        data class Rejected(val error: MaterializationError) : SinglePlan
    }
}
