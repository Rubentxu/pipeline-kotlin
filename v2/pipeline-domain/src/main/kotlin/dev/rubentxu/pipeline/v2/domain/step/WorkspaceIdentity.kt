package dev.rubentxu.pipeline.v2.domain.step

import java.nio.file.Path

/**
 * Runtime canonical workspace root supplied to a handler that needs to
 * resolve workspace-relative paths without consulting process-global
 * state (the `pipeline.workspace.root` system property).
 *
 * **WU-LPR-WC provenance**: this value class was hoisted from
 * `dev.rubentxu.pipeline.v2.application.Capabilities.kt` so plugin
 * handlers can declare `WORKSPACE_IDENTITY_CAPABILITY` in their
 * `StepContract.requiredCapabilities` and read the workspace root from
 * `StepHandlerContext.capabilities.get(...)` instead of closing over
 * a `() -> Path` resolver that reads the launcher system property.
 *
 * The capability is populated by
 * `dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess`
 * from the per-invocation `CanonicalRuntimeContext.shOptions.workspaceRoot`,
 * which the launcher threads into the canonical durable run. There is
 * NO shared mutable state: two plugin handlers in the same JVM can each
 * receive their own `WorkspaceIdentity` because the value comes from the
 * per-invocation context, not from a system property.
 *
 * Declared in `StepContract.requiredCapabilities`; admission is fail-closed
 * before the handler runs when it is not available.
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
