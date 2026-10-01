package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.PathAnchor
import dev.rubentxu.pipeline.v2.domain.workspace.PathResolution
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspacePathResolver
import java.nio.file.Path

/**
 * Read-only projection of where a Step currently operates, exposed as a Step
 * capability so handlers never reach the runtime context.
 *
 * This is the seam ADR-0100 introduces. Before it, the only way a handler could
 * learn its base was `WORKSPACE_IDENTITY_CAPABILITY`, whose value was built as
 *
 * ```kotlin
 * context.shOptions.workingDirectory ?: context.shOptions.workspaceRoot
 * ```
 *
 * That expression cannot represent a `dir` scope: it hands the *current
 * directory* out under the name *workspace root*, so a handler asking for the
 * security boundary receives the cwd instead, and a handler asking for the cwd
 * cannot tell it is not looking at the boundary.
 *
 * [ExecutionLocation] keeps both values apart. A Step that needs to resolve a
 * relative path declares a [PathAnchor] and gets a typed [PathResolution]; a
 * Step that needs to know who owns the root reads [workspace] and gets a
 * [WorkspaceLease] whose ownership is stated rather than inferred.
 *
 * Resolution is delegated to the pure [WorkspacePathResolver] in the domain, so
 * this adapter adds only the binding — no policy, no branching on Step identity.
 */
interface ExecutionLocationCapability {

    /** The stable, authorised workspace boundary plus the effective cwd. */
    val location: ExecutionLocation

    /** Shorthand for `location.workspace.root`. */
    val workspaceRoot: Path get() = location.workspace.root

    /** Shorthand for `location.cwd`. */
    val currentDirectory: Path get() = location.cwd

    /** Who owns the workspace root, as a typed state. */
    val lease: WorkspaceLease get() = location.workspace

    /**
     * Resolve and authorise a user path against [anchor].
     *
     * Returns a typed outcome; no effect is performed here. A handler applies
     * its filesystem operation only on [PathResolution.Resolved].
     */
    fun resolve(anchor: PathAnchor, userPath: String): PathResolution =
        WorkspacePathResolver.resolve(location, anchor, userPath)
}

/**
 * Adapts the runtime's legacy shell options into a typed [ExecutionLocation].
 *
 * This is the whole of the RP034-C bridge. It is deliberately a *derivation*:
 * the runtime keeps `ShOptions` as its transport for now, and this adapter is
 * the single place that decides what those two nullable fields mean.
 *
 * The mapping is exact and behaviour-preserving:
 *
 * - the lease root is the workspace the runtime actually resolved for this
 *   stage, i.e. the explicit project workspace when one was supplied, otherwise
 *   the per-stage scratch;
 * - the cwd is the `dir` scope projection when a nested scope is active,
 *   otherwise the lease root.
 *
 * When neither field is populated the location is anchored at the current
 * working directory, which is the only authority available; ownership is then
 * [WorkspaceLease.Managed] because an unrooted run cannot prove the directory is
 * user-owned, and managed is the fail-closed choice for destruction.
 */
class ShOptionsExecutionLocationAdapter private constructor(
    override val location: ExecutionLocation,
) : ExecutionLocationCapability {

    companion object {

        /**
         * Derive the typed location from the runtime's current shell options.
         *
         * [workspaceRoot] is the resolved workspace for this stage; pass `null`
         * only when the runtime has genuinely not resolved one.
         * [scopedWorkingDirectory] is the `dir` scope projection, if any.
         */
        fun from(
            workspaceRoot: Path?,
            scopedWorkingDirectory: Path?,
            fallbackRoot: Path,
        ): ExecutionLocationCapability {
            val root = (workspaceRoot ?: fallbackRoot).toAbsolutePath().normalize()
            val lease = WorkspaceLease.Managed(root)
            val cwd = (scopedWorkingDirectory ?: root).toAbsolutePath().normalize()
            return ShOptionsExecutionLocationAdapter(ExecutionLocation(lease, cwd))
        }
    }
}
