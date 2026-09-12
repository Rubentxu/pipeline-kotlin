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

/**
 * Typed seam for `core.deleteDir` (S2-A7 / G3-fix) — the ONLY capability
 * the registry-routed `CoreDeleteDirStep.handler` consumes to perform atomic
 * workspace directory deletion.
 *
 * ## Why a typed seam and not `DeleteDirExecutor` directly?
 *
 * AGENTS.md STEP IMPLEMENTATION — OPERATIVE GUIDE rule 9 (handler adapts to typed
 * seams, never embeds process/IO logic). The certified `core.sh` Step reaches a
 * `ShellOperations` capability and never touches `ProcessBuilder` directly. By
 * symmetry, `core.deleteDir` reaches this `DeleteDirOperations` capability and
 * never touches `DeleteDirExecutor`, `Files`, `EventSink`, or `sha256` directly.
 *
 * ## Scope
 *
 * The seam intentionally hides:
 * - [dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver] — canonical
 *   stage workspace resolution (workspace root guard enforced here).
 * - [dev.rubentxu.pipeline.v2.sdk.files.DeleteDirExecutor] — atomic filesystem
 *   semantics (walk+delete, workspace-root guard, `.deleted` marker).
 * - stage identity — derived from the adapter's runtime context binding.
 * - event emission — the adapter is the ONLY thing that emits `DirDeleted`.
 * - sha256 computation — the marker sha256 is computed by the executor and
 *   surfaced through the typed [DeleteDirResult].
 *
 * ## Failure semantics
 *
 * Implementations return [DeleteDirResult] (closed typed ADT). Re-classification
 * to [dev.rubentxu.pipeline.v2.domain.StepOutcome] is the responsibility of the
 * registry execution boundary.
 *
 * ## Reference
 *
 * Mirrors `WorkspaceOperations` (S2-A3 / G1) and `TemporaryWorkspaceOperations`
 * (S2-A6 / G3T). The adapter is constructed once per capability-access lookup,
 * binding the canonical runtime inputs so the handler receives only the typed seam.
 */
interface DeleteDirOperations {

    /**
     * Performs atomic workspace directory deletion for the given [DeleteDirInput].
     *
     * @param input The deleteDir input containing the path to delete.
     * @return The closed typed [DeleteDirResult] with path, deletedCount, and sha256.
     */
    fun delete(input: DeleteDirInput): DeleteDirResult
}

/**
 * Result of a [DeleteDirOperations.delete] operation.
 *
 * @property path Resolved absolute path that was deleted
 * @property deletedCount Number of files/directories deleted (0 if already deleted)
 * @property sha256 SHA-256 hex of the `.deleted` marker content
 */
data class DeleteDirResult(
    val path: String,
    val deletedCount: Int,
    val sha256: String,
)

val DELETE_DIR_OPERATIONS_CAPABILITY: StepCapability = StepCapability("delete-dir.operations")
 * Typed seam capability for milestone ordinal state operations (S2-A9 / spike).
 *
 * Declared by `CoreMilestoneStep` in its [dev.rubentxu.pipeline.v2.domain.step.StepContract].
 * The handler consumes [dev.rubentxu.pipeline.v2.application.MilestoneOperations] to
 * peek the current ordinal and advance it atomically — WITHOUT holding mutable state.
 *
 * The adapter ([dev.rubentxu.pipeline.v2.application.MilestoneOperationsAdapter]) binds to a
 * [dev.rubentxu.pipeline.v2.application.MilestoneStateStore] that lives at the
 * coordinator/run lifetime (created by coordinator wiring, not by handler invocation).
 * This mirrors the legacy `CanonicalMilestoneNodeDispatcher.lastReachedOrdinal` scope.
 *
 * ## Why a capability and not a var in the handler?
 *
 * AGENTS.md §STEP IMPLEMENTATION: handler MUST NOT hold mutable state. The previous
 * `CoreMilestoneStep` implementation used `private var lastReachedOrdinal: Int? = null`
 * in the singleton object — global classloader state that bleeds across runs and JVM
 * classloader boundaries. The capability system + run-scoped store provides proper
 * isolation.
 */
val MILESTONE_OPERATIONS_CAPABILITY: StepCapability =
    StepCapability("milestone.operations")
