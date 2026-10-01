package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceRequest
import java.nio.file.Path

/**
 * RP034-H / ADR-0101 — CLI workspace intent, resolved once at the boundary.
 *
 * The parser has already rejected `--isolated` together with `--workspace`, so
 * by the time these functions run the intent is unambiguous. The decision is a
 * pure function of the flags and the invocation directory, which is why the
 * whole mode matrix is testable without a process, a pipeline or a filesystem.
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
     * Separated from the interpretations below because the request is the
     * *decision* and the lease or transport is its *interpretation*; AGENTS.md
     * requires those two steps stay separable.
     */
    fun requestFor(flags: CliFlags): WorkspaceRequest =
        when {
            flags.isolated -> WorkspaceRequest.ManagedIsolated
            flags.workspace != null -> WorkspaceRequest.AttachExplicit(Path.of(flags.workspace))
            else -> WorkspaceRequest.AttachInvocationDirectory
        }

    /**
     * Interpret the request as a typed lease.
     *
     * [scratchParent] is consulted **only** for [WorkspaceRequest.ManagedIsolated],
     * and it is the parent under which per-stage workspaces are allocated, not a
     * resolved stage directory. The attached cases never read it, so a
     * control-plane path can never become a user workspace by accident
     * (INV-WS-004).
     */
    fun resolveWorkspaceLease(
        flags: CliFlags,
        invocationDirectory: Path,
        scratchParent: Path,
    ): WorkspaceLease =
        when (val request = requestFor(flags)) {
            is WorkspaceRequest.AttachInvocationDirectory ->
                WorkspaceLease.Attached(invocationDirectory.toAbsolutePath().normalize())

            is WorkspaceRequest.AttachExplicit ->
                WorkspaceLease.Attached(request.path.toAbsolutePath().normalize())

            is WorkspaceRequest.ManagedIsolated ->
                WorkspaceLease.Managed(scratchParent.toAbsolutePath().normalize())
        }

    /**
     * The transport the runtime receives, translating the typed lease into the
     * two facts the coordinator still needs today.
     *
     * Both facts are read from **one** resolved lease. A previous revision
     * returned only [base] and dropped ownership, and the runtime re-derived
     * `Managed` from it — so the destructive guard for a user-owned root never
     * fired in production even though the CLI had already decided `Attached`
     * (RP034-Id). Re-deciding from the flags in a second function would only
     * recreate that split; the lease is the single interpretation point.
     *
     * The asymmetry in [base] is deliberate and is the crux of the isolated
     * mode. A non-null base means "stages share this directory", which is
     * exactly the Attached semantics, so both attached cases pass their
     * directory.
     *
     * A PipelineK-managed workspace is expressed as **null base**: there is no
     * shared project directory to pin, so `WorkspaceResolver` resumes allocating
     * `<controlRoot>/workspace/<stageName>-<index>` per stage. Passing the
     * scratch parent as a base instead would pin every stage onto one directory
     * and silently destroy per-stage and per-branch isolation — a defect this
     * method exists to make impossible.
     *
     * [ownership] is *not* optional: `null` would reintroduce exactly the
     * ambiguity this pair removes.
     */
    fun resolveRuntimeTransport(
        flags: CliFlags,
        invocationDirectory: Path,
        scratchParent: Path,
    ): RuntimeWorkspaceTransport =
        when (val lease = resolveWorkspaceLease(flags, invocationDirectory, scratchParent)) {
            is WorkspaceLease.Attached ->
                RuntimeWorkspaceTransport(base = lease.root, ownership = lease.ownership)

            is WorkspaceLease.Managed ->
                RuntimeWorkspaceTransport(base = null, ownership = lease.ownership)
        }
}

/**
 * What crosses the CLI boundary into the runtime: where stages share a
 * directory, and who owns that directory.
 *
 * Grouped into one value because the two were previously decided in two places
 * and could disagree — which is exactly what happened, and what allowed
 * `deleteDir()` to erase a user's project under the local-first default.
 */
internal data class RuntimeWorkspaceTransport(
    val base: Path?,
    val ownership: WorkspaceOwnership,
)
