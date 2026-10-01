package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceRequest
import java.nio.file.Path

/**
 * RP034-H / ADR-0101 — CLI workspace intent, resolved once at the boundary.
 *
 * The parser has already rejected `--isolated` together with `--workspace`, so
 * by the time [resolveWorkspaceLease] runs the intent is unambiguous. The
 * decision is a pure function of the flags, the invocation directory and the
 * scratch root, which is why the whole mode matrix is testable without a
 * process, a pipeline or a filesystem.
 *
 * Semantics:
 * ```text
 * pipelinek run p.kts                    -> Attached(invocationDirectory),  USER
 * pipelinek run --workspace /path p.kts  -> Attached(/path),                USER
 * pipelinek run --isolated p.kts         -> Managed(scratch),               PIPELINEK
 * ```
 *
 * Nothing is inferred from a project marker, a build file, or the directory the
 * `.pipeline.kts` happens to live in. ADR-0102 rejects the VCS heuristic
 * precisely because it made the answer unpredictable; ADR-0101 keeps the CLI
 * deterministic by saying what it was asked to do.
 */
internal object WorkspaceIntent {

    /**
     * Normalise parsed flags into the closed [WorkspaceRequest] hierarchy.
     *
     * Separated from [resolveWorkspaceLease] because the request is the
     * *decision* and the lease is its *interpretation*; AGENTS.md requires those
     * two steps stay separable.
     */
    fun requestFor(flags: CliFlags): WorkspaceRequest =
        when {
            flags.isolated -> WorkspaceRequest.ManagedIsolated
            flags.workspace != null -> WorkspaceRequest.AttachExplicit(Path.of(flags.workspace))
            else -> WorkspaceRequest.AttachInvocationDirectory
        }

    /**
     * Interpret the request as a lease.
     *
     * [scratchRoot] is consulted **only** for [WorkspaceRequest.ManagedIsolated].
     * The attached cases never read it, so a control-plane path can never become
     * a user workspace by accident (INV-WS-004).
     */
    fun resolveWorkspaceLease(
        flags: CliFlags,
        invocationDirectory: Path,
        scratchRoot: Path,
    ): WorkspaceLease =
        when (val request = requestFor(flags)) {
            is WorkspaceRequest.AttachInvocationDirectory ->
                WorkspaceLease.Attached(invocationDirectory.toAbsolutePath().normalize())

            is WorkspaceRequest.AttachExplicit ->
                WorkspaceLease.Attached(request.path.toAbsolutePath().normalize())

            is WorkspaceRequest.ManagedIsolated ->
                WorkspaceLease.Managed(scratchRoot.toAbsolutePath().normalize())
        }
}
