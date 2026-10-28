package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore

/**
 * Whether the canonical cursor of a run moves forward, and to where.
 *
 * Closed because "the cursor moves" and "the cursor stays" are opposite outcomes and a
 * nullable cursor advance would let a caller read a `null` as a decision it never made.
 * The advance carries the two facts the write needs — the operation that was journaled and
 * the stage the run resumes at — so the effect never has to re-derive them from a context
 * the caller might have changed in between.
 */
internal sealed interface CursorAdvance {
    /** The cursor moves to [operationId] at [stageIndex]. */
    data class Advance(
        val operationId: String,
        val stageIndex: Int,
    ) : CursorAdvance

    /** The cursor stays where it is. */
    data object Hold : CursorAdvance
}

/**
 * The OWNER of the durable cursor: it decides whether the canonical run cursor moves, and it
 * performs the write that moves it.
 *
 * The replay cursor is TRAVERSAL state, so it lives here and nowhere else.
 * `ReplayCursor(runId, lastOpId, stageIndex, savedAt)` models where the canonical RUN resumes;
 * the values needed to write it (`runId`, `operationId`, `stageIndex`) are all facts of this
 * traversal and none of them are facts of an operation. The executor and the recovery engine
 * both lost their copy so that ownership is not split.
 *
 * This class is the CURRENT single authority, not a permanent name: the law is that the cursor
 * belongs to the canonical structural/run traversal, so this dependency moves with the
 * traversal if the traversal is ever refactored.
 *
 * The two rules are NOT the same, and the asymmetry is deliberate:
 *
 *  - after an EXECUTION, every outcome that is not a [StepOutcome.Failure] advances the cursor.
 *  - after a RECOVERY, only a recovered RUNNING operation that settled as a [StepOutcome.Success]
 *    advances it. Recovery has always been stricter than execution about Unstable, and the
 *    relocation of the cursor was not licensed to change what advances.
 *
 * The effect is ordered by contract and not by accident: the write happens only AFTER the
 * terminal journal append has returned, because a cursor pointing at an operation that was
 * never persisted is a lie the next run would believe.
 */
internal class CanonicalCursorAdvance(
    private val cursorStore: ReplayCursorStore,
) {
    /**
     * The rule of the EXECUTION path: an operation that did not fail advances the cursor.
     *
     * Pure: it reads the outcome and nothing else.
     */
    fun decideAfterExecution(
        operationId: String,
        stageIndex: Int,
        outcome: StepOutcome,
    ): CursorAdvance =
        if (outcome !is StepOutcome.Failure) {
            CursorAdvance.Advance(operationId, stageIndex)
        } else {
            CursorAdvance.Hold
        }

    /**
     * The rule of the RECOVERY path: only a recovered RUNNING operation that settled as a
     * success advances the cursor.
     *
     * Pure: it reads the reconciliation and the outcome, and observes nothing.
     */
    fun decideAfterRecovery(
        operationId: String,
        stageIndex: Int,
        resolution: InvocationReconciliation,
        outcome: StepOutcome,
    ): CursorAdvance =
        if (resolution is InvocationReconciliation.RecoverRunning && outcome is StepOutcome.Success) {
            CursorAdvance.Advance(operationId, stageIndex)
        } else {
            CursorAdvance.Hold
        }

    /**
     * Performs the write a decision names, if it names one.
     *
     * The caller MUST have already seen the terminal journal append return; the store's own
     * contract says the advance may only follow a persisted operation.
     */
    fun advance(runId: String, decision: CursorAdvance) {
        when (decision) {
            is CursorAdvance.Advance -> cursorStore.advance(runId, decision.operationId, decision.stageIndex)
            CursorAdvance.Hold -> Unit
        }
    }
}
