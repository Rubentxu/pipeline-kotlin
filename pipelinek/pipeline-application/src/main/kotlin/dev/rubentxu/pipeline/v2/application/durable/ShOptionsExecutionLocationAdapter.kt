package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import java.nio.file.Path

/**
 * Derives the typed [ExecutionLocation] from the runtime's legacy shell options.
 * This is the whole of the bridge. It is deliberately a *derivation*:
 * the runtime keeps `ShOptions` as its transport for now, and this is the single
 * place that decides what those two nullable fields mean.
 * The mapping is exact and behaviour-preserving:
 * - the lease root is the workspace the runtime actually resolved for this
 *   stage, i.e. the explicit project workspace when one was supplied, otherwise
 *   the per-stage scratch;
 * - the cwd is the `dir` scope projection when a nested scope is active,
 *   otherwise the lease root;
 * - the lease *case* is [ownership], which the CLI resolved at the boundary and
 *   carried across this transport. Ownership is never re-derived here.
 * When neither path field is populated the location is anchored at the current
 * working directory, which is the only authority available. When [ownership] is
 * absent the root is carried as [WorkspaceLease.Attached], the case that REFUSES
 * root destruction: an unstated owner has asserted nothing, and the only case
 * that grants the destructive permission is the one that asserts PipelineK
 * allocated this root. An unaccredited root is therefore never ours to destroy.
 * The derived [ExecutionLocation] IS the value carried by
 * `EXECUTION_LOCATION_CAPABILITY`** — the domain type, not a wrapper. An
 * earlier revision registered an `ExecutionLocationCapability` interface here
 * instead. That worked while only `:pipeline-application` read the key, and
 * broke as soon as the OFFICIAL_PLUGIN utilities family started
 * reading it: a plugin can only see `:pipeline-domain`, so it cast to
 * `ExecutionLocation` and every call failed with a `ClassCastException` at
 * runtime while the plugin's unit tests stayed green against a hand-built
 * context. Two types for one concept is the same collapsed-identity defect
 * Removes, one layer up; the capability value is therefore the domain
 * ADT and this adapter is only its derivation.
*/
object ShOptionsExecutionLocationAdapter {

    /**
     * Derive the typed location from the runtime's current shell options.
     * [workspaceRoot] is the resolved workspace for this stage; pass `null`
     * only when the runtime has genuinely not resolved one.
     * [scopedWorkingDirectory] is the `dir` scope projection, if any.
     * [ownership] is the explicit owner of [workspaceRoot] as decided by the
     * CLI boundary and carried on `ShOptions.workspaceOwnership`
     * This parameter is what makes the destructive guard reachable in
     * production. A previous revision ignored ownership and pinned
     * `WorkspaceLease.Managed` for every run, so a user who ran
     * `pipelinek run pipeline.kts` in their own project — which the local-first
     * default makes an *Attached*, USER-owned root — still received a PIPELINEK
     * lease, and `deleteDir()` was therefore permitted to erase the project.
     * The guard was live in the type and dead in the wiring; the installed
     * distribution reproduced it.
     * `null` means the owner was never stated, and it carries the REFUSING lease:
     * the destructive permission is only ever granted to a root whose owner is
     * known to be PipelineK. It used to be the other way round — `null` became
     * `Managed`, the permitting case — and the KDoc above called that the
     * "fail-closed choice for destruction" when it was the fail-OPEN one. The
     * permission was reachable with an unstated owner: `ShOptions.EMPTY`, the
     * default of `CanonicalDurableRunCoordinator`, states no owner and points at
     * a shared temp directory.
*/
    fun from(
        workspaceRoot: Path?,
        scopedWorkingDirectory: Path?,
        fallbackRoot: Path,
        ownership: WorkspaceOwnership? = null,
    ): ExecutionLocation {
        val root = (workspaceRoot ?: fallbackRoot).toAbsolutePath().normalize()
        val lease = when (ownership) {
            WorkspaceOwnership.USER -> WorkspaceLease.Attached(root)
            WorkspaceOwnership.PIPELINEK -> WorkspaceLease.Managed(root)
            null -> WorkspaceLease.Attached(root)
        }
        val cwd = (scopedWorkingDirectory ?: root).toAbsolutePath().normalize()
        return ExecutionLocation(lease, cwd)
    }
}
