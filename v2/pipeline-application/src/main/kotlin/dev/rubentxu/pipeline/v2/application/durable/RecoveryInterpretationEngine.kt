package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore

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
 *  - [InvocationReconciliation.RecoverRunning] runs the lifecycle boundary, writes the
 *    recovered terminal status, and advances the cursor only when the recovered outcome is a
 *    success.
 *  - [InvocationReconciliation.ReuseCompleted] settles a success with NO lifecycle event and
 *    NO journal write: the row is already terminal, and re-emitting would duplicate it.
 *  - [InvocationReconciliation.RejectedAbort] runs the lifecycle boundary around a typed
 *    INFRASTRUCTURE failure, because the step started and must finish observably.
 */
internal class RecoveryInterpretationEngine(
    private val eventSink: EventSink,
    private val journal: OperationJournal,
    private val cursorStore: ReplayCursorStore,
) {

    /**
     * The durable facts an interpretation needs. Deliberately not the whole runtime context:
     * this engine decides nothing, it only performs the effects its own resolution names.
     */
    data class Request(
        val operationId: String,
        val fingerprint: Fingerprint,
        val input: OperationInput,
        val runIdValue: String,
        val stageIndex: Int,
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

        is InvocationReconciliation.RecoverRunning -> {
            val executionResult = StepExecutionBoundary(eventSink).execute(request.lifecycleContext) {
                CommonExecutionResult(outcome = resolution.outcome, encodedOutput = null)
            }
            val outcome = executionResult.outcome
            journal.append(
                RerunOperation(
                    id = request.operationId,
                    fingerprint = request.fingerprint,
                    input = request.input,
                    output = null,
                    status = resolution.status,
                    attempt = 1,
                ),
            )
            if (outcome is StepOutcome.Success) {
                cursorStore.advance(request.runIdValue, request.operationId, request.stageIndex)
            }
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
}
