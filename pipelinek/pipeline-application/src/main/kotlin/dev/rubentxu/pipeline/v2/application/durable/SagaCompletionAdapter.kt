package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.SagaCompletionDecision
import dev.rubentxu.pipeline.v2.domain.saga.SagaCompletionReport
import dev.rubentxu.pipeline.v2.domain.saga.SagaDecider
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaSnapshot
import dev.rubentxu.pipeline.v2.events.durable.SagaObligationRow
import dev.rubentxu.pipeline.v2.events.durable.SagaObligationStore

/**
 * The engine's side of a Saga's COMPLETION.
 *
 * ## Why it is a port and not a method on the published runtime
 *
 * Closing a Saga well needs TWO things the published ports cannot express: the whole snapshot
 * of the Saga's obligations, and a write that moves ALL of them at once. The plan port answers
 * "what is owed?" for ONE decision at a time, and it cannot move several rows in one
 * transaction — which is exactly what closing a Saga is.
 *
 * So the completion arrives as an engine-internal port, and the only thing that crosses into
 * plugin hands is its REPORT.
 */
interface SagaCompletionOperations {

    /**
     * Closes [sagaId] by KEEPING its effects.
     *
     * @return what the durable state holds afterwards: the actions whose effects are kept, and
     * the Saga's state — settled, or needing a human.
     */
    fun complete(sagaId: SagaId): SagaCompletionReport
}

/**
 * The runtime implementation of the Saga COMPLETION.
 *
 * ## What it does, in three moves
 *
 *  1. Read the Saga's obligations and ask the pure decider whether the Saga may be completed.
 *  2. If it may, move every obligation whose effect was confirmed to
 *     [CompensationObligationStatus.EFFECT_KEPT] — ONE statement, so the Saga is closed for
 *     good and a resumed run never finds half of it owed.
 *  3. Read the rows back and report what the database says, not what the intention was.
 *
 * ## The refusal is not a warning
 *
 * A completion the durable state cannot corroborate is REFUSED, and the refusal names the
 * obligation that blocks it and the reason why. Nothing is written in that case, so the Saga
 * stays owed and a later process reads the same rows and reaches the same conclusion.
 *
 * ## The write is fenced
 *
 * The move carries the same lease epoch as every other write of the run, so a writer that lost
 * the run to a takeover cannot close a Saga it no longer owns. The refusal says so, with the
 * reason that says the completion did not happen.
 */
class SagaCompletionAdapter(
    private val runId: String,
    private val leaseEpoch: Long,
    private val store: SagaObligationStore,
    private val clock: Clock,
) : SagaCompletionOperations {

    override fun complete(sagaId: SagaId): SagaCompletionReport {
        val decision = SagaDecider.completion(snapshotOf(sagaId))

        val rows: List<SagaObligationRow>
        val directive: CompensationDirective
        when (decision) {
            is SagaCompletionDecision.Refused -> {
                rows = rowsOf(sagaId)
                directive = CompensationDirective.NeedsIntervention(
                    decision.obligation,
                    decision.reason,
                )
            }

            is SagaCompletionDecision.Completable -> {
                rows = discharge(sagaId)
                val stillOwed = rows.firstOrNull {
                    it.status == CompensationObligationStatus.EFFECT_CONFIRMED
                }
                directive = if (stillOwed == null) {
                    CompensationDirective.Settled(sagaId)
                } else {
                    // The rows are still owed, so the write did not happen: the durable state
                    // did not take the discharge, and the Saga is still open.
                    CompensationDirective.NeedsIntervention(
                        stillOwed.toObligation(),
                        InterventionReason.COMPLETION_NOT_RECORDED,
                    )
                }
            }
        }

        return SagaCompletionReport(
            sagaId = sagaId,
            kept = rows
                .filter { it.status == CompensationObligationStatus.EFFECT_KEPT }
                .map { it.actionId },
            directive = directive,
        )
    }

    /**
     * Moves the obligations whose effect is kept, and reads the rows back.
     *
     * The read-back is the answer: the report says what the database holds, so a caller that
     * reads the rows later reaches the same conclusion this one reached.
     */
    private fun discharge(sagaId: SagaId): List<SagaObligationRow> {
        store.moveAll(
            runId = runId,
            sagaId = sagaId,
            from = CompensationObligationStatus.EFFECT_CONFIRMED,
            to = CompensationObligationStatus.EFFECT_KEPT,
            updatedAtMs = clock.now().toEpochMilli(),
            leaseEpoch = leaseEpoch,
        )
        return rowsOf(sagaId)
    }

    /**
     * The durable obligations of [sagaId], as the decision sees them.
     *
     * The run's rows are the whole truth: an obligation that is not there was never promised,
     * and one that is there is the promise whatever the event log says.
     */
    private fun snapshotOf(sagaId: SagaId): SagaSnapshot = SagaSnapshot(
        sagaId = sagaId,
        obligations = rowsOf(sagaId).map { it.toObligation() },
    )

    /** The rows of [sagaId], oldest first. */
    private fun rowsOf(sagaId: SagaId): List<SagaObligationRow> =
        store.listForRun(runId).filter { it.sagaId == sagaId }
}
