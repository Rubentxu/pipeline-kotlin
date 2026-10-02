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
