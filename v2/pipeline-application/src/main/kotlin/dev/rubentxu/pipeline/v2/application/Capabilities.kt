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

/**
 * Runtime stage workspace observation supplied to a handler that must resolve the CURRENT
 * canonical stage workspace as a default (S2-A6 / G1), e.g. `core.pwd` returning its
 * per-stage workspace root.
 *
 * Deliberately carries ONLY the canonical stage workspace path — NOT a derived `pwd`
 * string and NOT a generic filesystem accessor: the WORKSPACE itself belongs to the
 * canonical execution substrate (WorkspaceResolver under controlDirRoot), and the Step
 * decides what to project from it. This separation lets the canonical path be decided
 * (G2) without changing how the workspace is acquired, and keeps the handler from
 * reading `System.getProperty("user.dir")` or `Paths.get(".")` directly.
 *
 * Bridge wiring: `CanonicalRuntimeCapabilityAccess.buildProvided` populates this
 * capability from `context.shOptions.workspaceRoot` (the same source the legacy
 * `pwdContext()` consumed) — preserving byte-equivalent truth with PATH_B.
 *
 * Declared in `StepContract.requiredCapabilities`; admission is fail-closed before
 * the handler runs when it is not available.
 */
data class WorkspaceIdentity(
    val workspaceRoot: java.nio.file.Path,
)

val WORKSPACE_IDENTITY_CAPABILITY: StepCapability = StepCapability("runtime.workspace-identity")

/**
 * Typed seam for `core.pwd.tmp` (S2-A6 / G3T post-correction) — the ONLY capability
 * the registry-routed `CorePwdTmpStep.handler` consumes to derive a deterministic
 * tmp workspace path.
 *
 * ## Why a typed seam and not a generic `OpId`/`Files` capability?
 *
 * AGENTS.md STEP IMPLEMENTATION — OPERATIVE GUIDE rule 9 (handler adapts to typed
 * seams, never embeds process/IO logic). The certified `core.sh` Step reaches a
 * `ShellOperations` capability and never touches `ProcessBuilder` directly. By
 * symmetry, the registry `core.pwd.tmp` reaches this `TemporaryWorkspaceOperations`
 * capability and never touches `Files.createDirectories` directly.
 *
 * ## Scope
 *
 * The seam intentionally hides:
 * - canonical OpId — owned by the runtime context (the canonical OpId is the
 *   durable identity anchor; the adapter, not the handler, derives the per-operation
 *   resource from it).
 * - workspaceRoot — owned by the canonical runtime context (`shOptions.workspaceRoot`).
 * - event sink — the adapter is the only place that emits `PwdResolved`.
 * - filesystem primitives — the adapter is the only place that calls
 *   `Files.createDirectories`.
 *
 * ## Failure semantics
 *
 * Implementations return [TempWorkspaceResult] (closed typed ADT). Re-classification
 * to [dev.rubentxu.pipeline.v2.domain.StepOutcome] is the responsibility of the
 * registry execution boundary.
 *
 * ## Determinism (D5–D12, S2-A6 / G3T)
 *
 * The adapter derives the path from `sha256(opId.format())` so the same OpId lands
 * on the same directory and a different OpId lands on a different directory. No
 * `System.currentTimeMillis()`, no `UUID.randomUUID()` on the path identity.
 */
interface TemporaryWorkspaceOperations {

    /**
     * Resolves (creating if necessary) the deterministic tmp workspace for the
     * current operation. Idempotent: invoking twice with the same OpId returns
     * the same path and does not throw on the second directory creation.
     */
    fun resolveOrCreate(): TempWorkspaceResult
}

data class TempWorkspaceResult(
    val path: String,
)

val TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY: StepCapability =
    StepCapability("workspace.temporary-operations")
