package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal

/**
 * TRAIN H3 / PR-019 — the INTERPRETATION half of durable recovery.
 *
 * [DurableInvocationResolver] decides. This interprets. The two were previously one `when`
 * block inside `CanonicalDurableRunCoordinator.dispatch`, which meant the coordinator both
 * decided what a resumed run may do and performed the journal write, the cursor advance and
 * the lifecycle events that followed from it. Splitting them is what lets the decision stay
 * pure and testable at HF0 (see `InvocationRecoveryCharacterizationTest`) while the effects
 * live behind this narrow port.
 *
 * ## Why the result is an ADT and not a nullable outcome
 *
 * `Execute` is not a recovery case: it is the absence of one. Returning `StepOutcome?` and
 * treating null as "carry on" would encode the caller's control flow in a sentinel and make
 * an unhandled resolution indistinguishable from a legitimate one. [RecoveryInterpretation]
 * states the two shapes explicitly, so adding a fourth resolution is a compile error here
 * rather than a silent fallthrough in the coordinator.
 *
 * ## The behaviour is moved, not rewritten
 *
 * Each arm is the coordinator's original arm verbatim, including which arms emit
 * StepStarted/StepFinished through [StepExecutionBoundary], which ones write a journal row and
 * which ones advance the replay cursor:
 *
 *  - [InvocationReconciliation.Diverged] settles a typed INFRASTRUCTURE failure and does NOT
 *    emit lifecycle events, because the step never starts.
 *  - [InvocationReconciliation.RecoverRunning] runs the lifecycle boundary and writes the
 *    recovered terminal status.
 *  - [InvocationReconciliation.ReuseCompleted] settles a success with NO lifecycle event and
 *    NO journal write: the row is already terminal, and re-emitting would duplicate it.
 *  - [InvocationReconciliation.RejectedAbort] runs the lifecycle boundary around a typed
 *    INFRASTRUCTURE failure, because the step started and must finish observably.
 *
 * ## ADR-0103 D7 — no replay cursor, and no traversal coordinates
 *
 * This engine used to take a `ReplayCursorStore` and advance it on a recovered success, and
 * its [Request] carried `runIdValue` + `stageIndex` purely to do so. Both are gone. The
 * cursor models where the canonical RUN resumes, which is traversal state; interpreting a
 * recovery is not traversal. Leaving the store here would also have split cursor ownership
 * between this engine and the executor, which is the two-owner shape D7 exists to remove.
 *
 * The consequence is the signal that the boundary is now correct: with `runIdValue` and
 * `stageIndex` deleted as unused, every remaining field of [Request] is a durable fact
 * about the operation or the lifecycle coordinates of the execution. Nothing here needs a
 * canonical stage position, so a frontend that has none can reuse this engine as-is.
 */
internal class RecoveryInterpretationEngine(
    private val eventSink: EventSink,
    private val journal: OperationJournal,
) {

    /**
     * The durable facts an interpretation needs. Deliberately not the whole runtime context:
     * this engine decides nothing, it only performs the effects its own resolution names.
     *
     * `runIdValue` and `stageIndex` were removed under ADR-0103 D7. They existed only to
     * advance the replay cursor, and an unused field is exactly how a responsibility
     * reappears in the wrong class later.
     */
    data class Request(
        val operationId: String,
        val fingerprint: Fingerprint,
        val input: OperationInput,
        val lifecycleContext: StepLifecycleContext,
    )

    /** The two shapes every resolution can take. Closed: a new resolution will not compile. */
    sealed interface RecoveryInterpretation {
        /** The invocation is settled; the caller returns this outcome without executing. */
        data class Settled(val outcome: StepOutcome) : RecoveryInterpretation

        /** Not a recovery case: the caller proceeds to the effective executor. */
        data object ProceedToExecution : RecoveryInterpretation
    }

    /**
     * Suspend because interpretation is the EFFECTFUL half: two of the four arms run the
     * [StepExecutionBoundary], which emits lifecycle events around a body. The decision
     * ([DurableInvocationResolver]) is the pure half and stays non-suspend — the split is
     * exactly where the effects start.
     */
    suspend fun interpret(
        resolution: InvocationReconciliation,
        request: Request,
    ): RecoveryInterpretation = when (resolution) {
        is InvocationReconciliation.Diverged -> RecoveryInterpretation.Settled(
            StepOutcome.Failure(
                PipelineFailure(
                    FailureKind.INFRASTRUCTURE,
                    "Canonical run diverged at '${resolution.operationId}'",
                ),
            ),
        )

        // ADR-0103 R1-E: a required recovery whose substrate could not be inspected.
        //
        // Deliberately effect-free, and that is the whole design. A failure that appended a terminal
        // row — SUCCEEDED, FAILED or LOST alike — would not be fail-closed, it would be
        // fail-destroyed: the RUNNING row is the only evidence that this operation is still in
        // flight, and a later, correctly configured run needs it in order to reconcile. So the
        // invocation fails, and the durable state is left exactly as it was found.
        //
        // LOST in particular is the wrong terminal here. LOST means "the control root was readable and
        // the operation directory held nothing recoverable"; this means "there was no control root to
        // read". The observer keeps those apart in RunningSubprocessObservation, and so does this arm.
        is InvocationReconciliation.RecoveryUnobservable -> RecoveryInterpretation.Settled(
            StepOutcome.Failure(
                PipelineFailure(
                    FailureKind.INFRASTRUCTURE,
                    "Recovery of '${resolution.operationId}' is required by the declared recovery " +
                        "policy but the subprocess substrate could not be observed, so no conclusion " +
                        "about its external effect is possible. The operation is left RUNNING: it was " +
                        "neither re-executed nor closed. Configure the runtime control root and re-run " +
                        "to reconcile it.",
                ),
            ),
        )

        is InvocationReconciliation.RecoverRunning -> {
            // ADR-S4-R1 §2.4. The resolution arrives CLOSED. Both arms below are mechanical
            // projections of one semantic terminal, and neither re-classifies anything: an engine
            // that received `Completed` / `TimedOut` / `Lost` and decided a terminal from them
            // would be a second semantic authority outside the decision core, which is the exact
            // defect this class was split to remove.
            val terminal = resolution.terminal
            val executionResult = StepExecutionBoundary(eventSink).execute(request.lifecycleContext) {
                CommonExecutionResult(outcome = terminal.asStepOutcome(), encodedOutput = null)
            }
            val outcome = executionResult.outcome
            journal.append(
                RerunOperation(
                    id = request.operationId,
                    fingerprint = request.fingerprint,
                    input = request.input,
                    output = null,
                    status = terminal.asOperationStatus(),
                    attempt = 1,
                ),
            )
            RecoveryInterpretation.Settled(outcome)
        }

        // Reuse is the one arm that emits nothing: the journal row is already terminal, so
        // re-emitting StepStarted/StepFinished or re-appending it would duplicate the record.
        InvocationReconciliation.ReuseCompleted -> RecoveryInterpretation.Settled(StepOutcome.Success)

        is InvocationReconciliation.RejectedAbort -> RecoveryInterpretation.Settled(
            StepExecutionBoundary(eventSink).execute(request.lifecycleContext) {
                CommonExecutionResult(
                    outcome = StepOutcome.Failure(
                        PipelineFailure(
                            FailureKind.INFRASTRUCTURE,
                            "Replay aborted for '${resolution.operationId}'",
                        ),
                    ),
                    encodedOutput = null,
                )
            }.outcome,
        )

        InvocationReconciliation.Execute -> RecoveryInterpretation.ProceedToExecution
    }

    /**
     * The single mechanical projection of a semantic terminal onto a pipeline outcome.
     *
     * [RecoveredTerminal.Failed] is NOT re-inspected for `FailureKind.TIMEOUT`. Before
     * [RecoveredTerminal] existed, the status projection read `failure.kind == TIMEOUT` to decide
     * between `FAILED` and `FAILED_TIMEOUT`, which meant the durable status was a second guess
     * about a fact the observer had already resolved — the watchdog flag is on the filesystem, not
     * in a message. `TimedOut` is its own case now, so the guess is gone.
     */
    private fun RecoveredTerminal.asStepOutcome(): StepOutcome = when (this) {
        RecoveredTerminal.Succeeded -> StepOutcome.Success
        is RecoveredTerminal.Failed -> StepOutcome.Failure(failure)
        is RecoveredTerminal.TimedOut -> StepOutcome.Failure(failure)
        is RecoveredTerminal.Lost -> StepOutcome.Failure(failure)
    }

    /**
     * The single mechanical projection of a semantic terminal onto the durable storage vocabulary.
     *
     * Kept adjacent to [asStepOutcome] and exhaustive over the same closed ADT on purpose: a fourth
     * terminal added tomorrow fails to COMPILE in both, rather than silently persisting under the
     * wrong status in one of them.
     */
    private fun RecoveredTerminal.asOperationStatus(): OperationStatus = when (this) {
        RecoveredTerminal.Succeeded -> OperationStatus.SUCCEEDED
        is RecoveredTerminal.Failed -> OperationStatus.FAILED
        is RecoveredTerminal.TimedOut -> OperationStatus.FAILED_TIMEOUT
        is RecoveredTerminal.Lost -> OperationStatus.LOST
    }
}
