package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import java.nio.file.Path

/**
 * Derives the typed [ExecutionLocation] from the runtime's legacy shell options.
 *
 * This is the whole of the RP034-C bridge. It is deliberately a *derivation*:
 * the runtime keeps `ShOptions` as its transport for now, and this is the single
 * place that decides what those two nullable fields mean.
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
 *
 * **The derived [ExecutionLocation] IS the value carried by
 * `EXECUTION_LOCATION_CAPABILITY`** — the domain type, not a wrapper. An
 * earlier revision registered an `ExecutionLocationCapability` interface here
 * instead. That worked while only `:pipeline-application` read the key, and
 * broke as soon as the OFFICIAL_PLUGIN utilities family (RP034-E) started
 * reading it: a plugin can only see `:pipeline-domain`, so it cast to
 * `ExecutionLocation` and every call failed with a `ClassCastException` at
 * runtime while the plugin's unit tests stayed green against a hand-built
 * context. Two types for one concept is the same collapsed-identity defect
 * ADR-0100 removes, one layer up; the capability value is therefore the domain
 * ADT and this adapter is only its derivation.
 */
object ShOptionsExecutionLocationAdapter {

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
    ): ExecutionLocation {
        val root = (workspaceRoot ?: fallbackRoot).toAbsolutePath().normalize()
        val lease = WorkspaceLease.Managed(root)
        val cwd = (scopedWorkingDirectory ?: root).toAbsolutePath().normalize()
        return ExecutionLocation(lease, cwd)
    }
}
