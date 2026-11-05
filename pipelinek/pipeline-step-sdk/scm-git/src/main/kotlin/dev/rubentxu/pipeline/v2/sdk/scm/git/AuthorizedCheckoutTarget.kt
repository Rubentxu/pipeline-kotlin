package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.sdk.WorkspacePathAnchors
import java.nio.file.Path

/**
 * The ONE directory a checkout is allowed to write to.
 *
 * **The guarantee.** The repository is materialised inside the workspace, and
 * nothing else is: a `relativeTargetDir` that leaves the workspace is refused
 * BEFORE a credential is applied, a directory is created or a git process is
 * started.
 *
 * **Why the type exists.** The destination used to be computed twice, by two
 * different rules, and the strict one ran last:
 *
 * - the executor resolved `workspaceRoot.resolve(relativeTargetDir)` — no
 *   normalisation, no confinement — and worked there;
 * - the handler validated the same user path through [WorkspacePathAnchors]
 *   AFTER the executor had already cloned and written files.
 *
 * A rejection that arrives after the effect is not a guard, it is a note. The
 * destination is now resolved once, by the authority that already owns path
 * confinement in this repository, and the resolved path travels to the executor
 * as a value that cannot be built any other way.
 *
 * **Why the constructor is private.** A destination that anyone can assemble
 * from an arbitrary path is a destination that can be wrong. The only way to
 * obtain one is [resolve], which refuses what the workspace refuses.
 */
class AuthorizedCheckoutTarget private constructor(
    /** The absolute directory where the repository is materialised. */
    val path: Path,
) {

    override fun toString(): String = path.toString()

    override fun equals(other: Any?): Boolean =
        other is AuthorizedCheckoutTarget && other.path == path

    override fun hashCode(): Int = path.hashCode()

    companion object {

        /**
         * Resolve [relativeTargetDir] against [location] and authorise it.
         *
         * The path is anchored on the current directory — a `dir(...)` scope
         * moves where the repository lands — and the workspace root is the
         * boundary it may not cross. A relative path that traverses out, an
         * absolute path outside the root, and an empty path are refused with
         * the typed USER failure the path authority already projects.
         *
         * Pure with respect to the filesystem: no directory is created and no
         * process is started, so a refusal costs nothing.
         */
        fun resolve(
            location: ExecutionLocation,
            stepKey: String,
            relativeTargetDir: String,
        ): AuthorizedCheckoutTarget =
            AuthorizedCheckoutTarget(
                WorkspacePathAnchors.currentDirectory(location, stepKey, relativeTargetDir),
            )
    }
}
