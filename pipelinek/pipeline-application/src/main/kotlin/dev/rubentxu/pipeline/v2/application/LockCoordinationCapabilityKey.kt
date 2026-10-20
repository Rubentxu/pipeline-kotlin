package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * Capability key under which the engine supplies a typed [LockCoordinator] to a
 * handler that must take a shared resource (RP6-A / WU-091).
 *
 * Mirrors [SHELL_OPERATIONS_CAPABILITY]:
 *
 * - Kept at a neutral owner so the durable runtime capability bridge and the
 *   registry step definitions reference the SAME token without the execution
 *   seam depending on any concrete Step definition.
 * - A handler may use it only if it declares it in its
 *   [dev.rubentxu.pipeline.v2.domain.step.StepContract.requiredCapabilities];
 *   admission is fail-closed before the handler runs when it is not available.
 * - `context.capabilities.get<LockCoordinator>(LOCK_COORDINATION_CAPABILITY)` is
 *   the only call site a `lock` handler is allowed to use for coordination.
 *
 * `core.lock` declares this TOGETHER WITH
 * [dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY], because
 * the two are halves of one declaration: the handler needs the continuation to
 * run the body it gated, and needs this port to decide whether to run it at all.
 * `resolveBodyExecutionPolicy` rejects a `HANDLER_CONTINUATION` Step that is
 * missing the continuation capability, so the pairing is checked, not assumed.
 */
val LOCK_COORDINATION_CAPABILITY: StepCapability = StepCapability("lockCoordination")

/**
 * Capability key under which the engine supplies the current
 * [ExecutionLaneId] (RP6-A / WU-091).
 *
 * ## Why this is a capability and not a wider `StepHandlerContext`
 *
 * The lane is needed by `core.lock` to decide re-entrancy, and nothing else needs
 * it today. Widening `StepHandlerContext` would push a parallel-lineage fact onto
 * every Step handler in the system to serve one of them, and would make the
 * context a second source of identity beside the operation id it is derived from.
 *
 * The narrow seam keeps the direction: the handler asks for the lane, the bridge
 * derives it from the runtime's own operation identity, and a Step that does not
 * declare this capability cannot observe it.
 *
 * `core.lock` declares this TOGETHER WITH
 * [dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY] and
 * [LOCK_COORDINATION_CAPABILITY]; all three are fail-closed at admission.
 */
val EXECUTION_LANE_CAPABILITY: StepCapability = StepCapability("executionLane")
