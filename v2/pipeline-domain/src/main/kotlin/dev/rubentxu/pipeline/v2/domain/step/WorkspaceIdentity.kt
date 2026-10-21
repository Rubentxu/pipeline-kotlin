package dev.rubentxu.pipeline.v2.domain.step

import java.nio.file.Path

/**
 * Runtime canonical workspace root supplied to a handler that needs to
 * resolve workspace-relative paths without consulting process-global
 * state (the `pipeline.workspace.root` system property).
 *
 * The capability is populated by the runtime capability bridge from
 * the per-invocation `CanonicalRuntimeContext.shOptions.workspaceRoot`,
 * which the launcher threads into the canonical durable run. There is
 * NO shared mutable state: two plugin handlers in the same JVM can each
 * receive their own `WorkspaceIdentity` because the value comes from the
 * per-invocation context, not from a system property.
 *
 * Declared in `StepContract.requiredCapabilities`; admission is
 * fail-closed before the handler runs when it is not available.
 */
data class WorkspaceIdentity(
    val workspaceRoot: Path,
)

/**
 * Capability key under which the engine supplies a [WorkspaceIdentity] to
 * a handler. Mirrors `BODY_INVOKER_CAPABILITY` as a domain-level capability
 * token: plugins consume it, the runtime bridge populates it, neither
 * depends on the other.
 */
val WORKSPACE_IDENTITY_CAPABILITY: StepCapability = StepCapability("runtime.workspace-identity")

/**
 * Capability key for the typed execution location.
 *
 * Declared here, alongside [WORKSPACE_IDENTITY_CAPABILITY], so a plugin can
 * declare the new seam in `StepContract.requiredCapabilities` without
 * depending on `:pipeline-application`, where the adapter lives.
 *
 * The value supplied under this key is
 * `dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation`, which
 * carries the stable workspace root AND the effective current directory as
 * separate fields. That separation is the reason it exists:
 * [WorkspaceIdentity] has a single `workspaceRoot` that the runtime bridge
 * had to populate with `workingDirectory ?: workspaceRoot`, making the two
 * authorities indistinguishable to a handler.
 *
 * Migration is incremental — this key is registered alongside the legacy
 * one and consumers move onto it per vertical, so no handler is forced to
 * migrate at once. The legacy capability is removed once no handler remains
 * on it.
 */
val EXECUTION_LOCATION_CAPABILITY: StepCapability = StepCapability("runtime.execution-location")
