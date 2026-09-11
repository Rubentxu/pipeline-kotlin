package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * Capability key under which the engine supplies the [dev.rubentxu.pipeline.v2.events.EventSink] to a
 * handler that must emit typed output/domain events.
 *
 * Kept at a neutral owner (CDE.3-d1) so the durable runtime capability bridge and the registry step
 * definitions can both reference the SAME token without the execution seam depending on any concrete
 * plugin definition (e.g. `core.echo`). A handler may use the capability only if it declares it in its
 * [dev.rubentxu.pipeline.v2.domain.step.StepContract.requiredCapabilities]; admission is fail-closed
 * before the handler runs when it is not available.
 */
val EVENT_SINK_CAPABILITY: StepCapability = StepCapability("eventSink")

/**
 * Capability key under which the engine supplies the [WorkspaceOperations] typed seam to a
 * handler that must perform stage-workspace file operations (S2-A3 / G1).
 *
 * Neutral owner, mirroring [EVENT_SINK_CAPABILITY] and `SHELL_OPERATIONS_CAPABILITY`: the
 * durable runtime capability bridge and the registry Step definitions reference the SAME
 * token without the execution seam depending on any concrete Step definition (e.g.
 * `core.file.writeFile`). A handler may use the capability only if it declares it in its
 * `StepContract.requiredCapabilities`; admission is fail-closed before the handler runs
 * when it is not available.
 */
val WORKSPACE_OPERATIONS_CAPABILITY: StepCapability = StepCapability("workspaceOperations")

/**
 * Runtime stage identity supplied to a handler that must resolve the CURRENT stage as a
 * default (S2-A4 / G1), e.g. `core.emit.event`'s `StageMarkedUnstable` stageName fallback.
 *
 * Deliberately a narrow value (name + index) — NOT a step toward a PipelineContext: the
 * handler sees only this immutable pair, derived by the durable capability bridge from the
 * runtime context, exactly like [EVENT_SINK_CAPABILITY]. Declared in
 * `StepContract.requiredCapabilities`; admission is fail-closed before the handler runs
 * when it is not available.
 */
data class StageIdentity(
    val name: String,
    val index: Int,
)

val STAGE_IDENTITY_CAPABILITY: StepCapability = StepCapability("runtime.stage-identity")

/**
 * Runtime platform observation supplied to a handler that must classify the CURRENT
 * execution environment (S2-A5 / G1), e.g. `core.isUnix`.
 *
 * Deliberately carries ONLY the raw observation ([PlatformIdentity.osName]) — NOT a
 * derived `isUnix` boolean: the classification POLICY belongs to the Step, the
 * environmental OBSERVATION belongs to this capability. This separation lets the
 * canonical policy be decided/compared (G2) without changing how the environment is
 * acquired, and keeps the handler from calling `System.getProperty` directly.
 *
 * Declared in `StepContract.requiredCapabilities`; admission is fail-closed before
 * the handler runs when it is not available.
 */
data class PlatformIdentity(
    val osName: String,
)

val PLATFORM_IDENTITY_CAPABILITY: StepCapability = StepCapability("runtime.platform-identity")
