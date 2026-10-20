package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput

/**
 * Typed output carrier for the `core.lock` handler (RP6-A / WU-091).
 *
 * Implements [TypedStepOutput], and that is not decoration. `core.lock` is a
 * `HANDLER_CONTINUATION` Step, so the body is invoked BY THE HANDLER and its
 * verdict arrives as a returned [dev.rubentxu.pipeline.v2.domain.step.BodyOutcome]
 * rather than propagating on its own. Without this carrier the boundary would see
 * a successful handler and report `lock` as `Success` over a body that failed.
 *
 * The property is pinned by
 * `LockFeasibilityProofTest.a body that reports failure still releases and fails
 * the step`, which goes red if the carrier stops implementing the interface.
 *
 * Same mechanism, same reason, as [CoreShellOutput] for `core.sh`.
 *
 * @param resource the resource this invocation acted on.
 * @param bodyRan whether the body was invoked. `false` on a declined
 *   acquisition, which is a normal outcome for `skipIfLocked` and NOT a failure.
 * @param admission why the resource was or was not taken. Carried in the output so
 *   a reader of the journal can tell "took it and the body succeeded" from "never
 *   took it", which are otherwise the same `Success` outcome.
 * @param outcome the verdict the execution boundary must record. On an acquired
 *   hold this is the body's own [StepOutcome]; on a denial it is the Step's
 *   failure or success, per [LockDenialReason].
 */
data class CoreLockOutput(
    val resource: String,
    val bodyRan: Boolean,
    val admission: LockAdmission,
    override val outcome: StepOutcome,
) : TypedStepOutput
