package dev.rubentxu.pipeline.v2.domain.workspace

import java.nio.file.Path

/**
 * Who owns a workspace directory, and therefore who may destroy it.
 *
 * This is a **typed state**, never an inference. A previous implementation
 * derived ownership by looking for a `.git`/`.hg`/`.svn` marker
 * (`ProjectCheckoutDetector`), which made a bare non-VCS project tree readable as
 * disposable scratch and left the safety of the user's checkout dependent on the
 * type of version control they happened to use.
 *
 * ADR-0102 replaces the heuristic: ownership is decided when the lease is
 * created and is carried explicitly from then on.
 */
enum class WorkspaceOwnership {
    /** A directory the user owns. Destructive root operations fail closed. */
    USER,

    /** A directory PipelineK allocated and may clean up. */
    PIPELINEK,
}

/**
 * A workspace directory together with its explicit ownership.
 *
 * The two cases are distinct types rather than a `Path` plus a boolean so that
 * an invalid combination cannot be represented: there is no way to hold an
 * "attached" lease that claims PipelineK ownership, and the compiler forces
 * every destructive decision to state which case it is in.
 */
sealed interface WorkspaceLease {

    /** The absolute, normalised workspace root. Stable for the lease lifetime. */
    val root: Path

    /** Ownership of [root]. Constant per case, so it cannot drift from the type. */
    val ownership: WorkspaceOwnership

    /**
     * A workspace the user owns — their project checkout, or any directory they
     * named explicitly.
     *
     * Root-wide `deleteDir`/`cleanWs` must fail closed. Subdirectory operations
     * are allowed once they pass confinement.
     */
    data class Attached(override val root: Path) : WorkspaceLease {
        override val ownership: WorkspaceOwnership = WorkspaceOwnership.USER
    }

    /**
     * A workspace PipelineK allocated and manages — the historical per-stage
     * scratch behaviour, now requested explicitly via `--isolated`.
     *
     * Lifecycle cleanup of the root remains permitted.
     */
    data class Managed(override val root: Path) : WorkspaceLease {
        override val ownership: WorkspaceOwnership = WorkspaceOwnership.PIPELINEK
    }
}

/**
 * Where a run currently operates: the stable workspace boundary plus the
 * effective current directory.
 *
 * [cwd] is non-null by construction and starts equal to `workspace.root`. It
 * changes only by deriving a child value (see `dir`), never by mutating a global
 * process state, and leaving a scope restores the parent value.
 *
 * The two fields are kept distinct even when they hold the same path, because
 * they answer different questions: [WorkspaceLease.root] is the security
 * boundary, [cwd] is where the next operation happens. Collapsing them — the
 * `workingDirectory ?: workspaceRoot` expression this type replaces — is what
 * allowed a `dir` scope to silently redefine the workspace.
 */
data class ExecutionLocation(
    val workspace: WorkspaceLease,
    val cwd: Path,
) {
    init {
        require(cwd.isAbsolute) { "cwd must be absolute, was: $cwd" }
        require(workspace.root.isAbsolute) {
            "workspace root must be absolute, was: ${workspace.root}"
        }
    }
}

/**
 * The conceptual base a workspace-aware Step resolves its user paths against.
 *
 * A Step declares which base it means instead of inferring it. `ControlRoot` is
 * deliberately **not** a member: control-plane data flows through dedicated
 * ports and must never become the implicit base of a user-supplied path
 * (INV-WS-004).
 */
enum class PathAnchor {
    /** Resolve against the effective current directory. */
    CURRENT_DIRECTORY,

    /** Resolve against the stable workspace root. */
    WORKSPACE_ROOT,
}

/**
 * The user's intent at the CLI boundary, normalised before any allocation.
 *
 * Modelled as a closed hierarchy rather than `workspaceBase: Path?` so that
 * "no flag" is an explicit intent instead of a null that downstream code
 * reinterprets. This is the type that makes `--workspace` and `--isolated`
 * mutually exclusive by construction rather than by a later precedence rule.
 */
sealed interface WorkspaceRequest {

    /** `pipelinek run p.kts` — attach the directory the user invoked from. */
    data object AttachInvocationDirectory : WorkspaceRequest

    /** `pipelinek run --workspace <path> p.kts` — attach an explicit directory. */
    data class AttachExplicit(val path: Path) : WorkspaceRequest

    /** `pipelinek run --isolated p.kts` — PipelineK-managed scratch. */
    data object ManagedIsolated : WorkspaceRequest
}

/**
 * How stages and branches share an allocated workspace.
 *
 * Allocation is a separate dimension from origin: a workspace's *origin* (user
 * vs PipelineK) is [WorkspaceLease], while its *sharing* is this policy. Keeping
 * them apart stops a future change in sharing from reinterpreting the origin.
 */
sealed interface WorkspaceAllocationPolicy {
    data object RunShared : WorkspaceAllocationPolicy
    data object StageIsolated : WorkspaceAllocationPolicy
    data object BranchIsolated : WorkspaceAllocationPolicy
}

/**
 * Expected operational failures of workspace-scoped path handling.
 *
 * These are values, not exceptions: path rejection is normal domain control
 * flow, and a Step must be able to report it as a typed outcome.
 */
sealed interface WorkspacePathError {
    data class InvalidPath(val raw: String) : WorkspacePathError
    data class AbsolutePathNotAllowed(val raw: String) : WorkspacePathError
    data class EscapesWorkspace(val raw: String, val root: Path) : WorkspacePathError
    data class SymlinkEscape(val path: Path, val root: Path) : WorkspacePathError

    /** A root-wide destructive Step targeted an Attached workspace. */
    data class ProtectedWorkspaceRoot(val operation: String, val root: Path) : WorkspacePathError

    data class WorkspaceUnavailable(val reason: String) : WorkspacePathError

    /** `--isolated` and `--workspace` were both requested. */
    data class ConflictingWorkspaceMode(
        val invocationDirectory: Path,
        val explicit: Path,
    ) : WorkspacePathError
}

/**
 * Outcome of resolving and authorising one user-supplied path.
 *
 * The project's closed result algebra (AGENTS.md: prefer sealed ADTs for
 * outcomes with distinct semantics). Deliberately **not** a boolean paired with
 * a nullable `Path`: the rejection carries a typed reason that a Step can
 * project into a user-facing diagnostic, and there is no representation of
 * "resolved to nothing".
 */
sealed interface PathResolution {

    /** The authorised absolute path. Effects may now be applied against it. */
    data class Resolved(val path: Path) : PathResolution

    /** The path was refused, with the reason. No effect was applied. */
    data class Rejected(val reason: WorkspacePathError) : PathResolution
}

/**
 * Outcome of a root-wide destructive authorisation.
 *
 * Same algebra as [PathResolution]: permission carries a typed refusal rather
 * than a false flag, so `deleteDir` on a user checkout cannot be mistaken for
 * "not applicable".
 */
sealed interface DestructiveAuthorization {
    data object Permitted : DestructiveAuthorization
    data class Refused(val reason: WorkspacePathError) : DestructiveAuthorization
}

