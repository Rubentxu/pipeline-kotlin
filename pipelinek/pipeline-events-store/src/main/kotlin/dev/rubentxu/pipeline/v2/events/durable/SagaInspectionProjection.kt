package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionView
import dev.rubentxu.pipeline.v2.domain.saga.SagaObligationStanding
import dev.rubentxu.pipeline.v2.domain.saga.SagaOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaPromiseOrigin
import dev.rubentxu.pipeline.v2.domain.saga.SagaRunView
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.identity.RunPresentationProjection

/**
 * Where one promise comes from.
 *
 * The key says WHICH promise, and the origin says who made it and under which version of the
 * intent contract. An operator reading a run needs both: knowing that a promise is pending is
 * not enough to know who has to answer for it.
 */
data class SagaInspectedOrigin(
    val sagaId: String,
    val actionId: String,
    val compensationId: String,
    val origin: SagaPromiseOrigin,
)

/**
 * What an operator reads of a run: what the run owes, and where each promise comes from.
 *
 * The two travel together because they are read from the same rows: an operator that had to
 * join them apart could see a promise with no origin, and a promise with no origin is
 * indistinguishable from a promise nobody made.
 */
data class SagaInspectionReport(
    val view: SagaRunView,
    val origins: List<SagaInspectedOrigin>,
) {
    /**
     * The origin of the promise under [key], when the run has one.
     */
    fun originOf(sagaId: String, actionId: String, compensationId: String): SagaPromiseOrigin? =
        origins.firstOrNull {
            it.sagaId == sagaId && it.actionId == actionId && it.compensationId == compensationId
        }?.origin
}

/**
 * Presents the durable promises of a run as the work they leave behind.
 *
 * The projection adds nothing of its own: the resource, the status, the receipt and the plan
 * fingerprint are the ones the engine wrote, and the phase, the next step and the classified
 * error are the reading of that status. A reader that had to interpret a log to answer "what
 * does this run owe?" would be answering a different question, and would answer it
 * differently after a crash.
 *
 * ## The two things it reads, and why both are needed
 *
 * The promises say WHAT IS OWED. The run's announcements say HOW THE SAGA ENDED, and that
 * changes what an operator has to do: a saga that stopped leaves promises owed that the engine
 * will not touch again, so every promise still in play is now waiting for a person.
 *
 * The announcements are read with the projection the plane's own module publishes, so this
 * file has no opinion about what an announcement means, and a run whose announcements cannot
 * be read is reported as needing a person rather than as owing nothing.
 */
object SagaInspectionProjection {

    /**
     * Presents the promises of [runId].
     *
     * The order is the order the promises were made in, which is the order the compensations
     * would run in.
     */
    fun read(
        runId: String,
        rows: List<SagaObligationRow>,
        events: List<DomainEvent>,
    ): SagaInspectionReport {
        val presentation = RunPresentationProjection.read(runId, events)
        val stoppedSagas = presentation.sagas
            .filterIsInstance<SagaOutcome.NeedsIntervention>()
            .map { it.sagaId }
            .toSet()

        val actions = rows.map { row ->
            val standing = SagaObligationStanding.of(row.status)
            val sagaStopped = row.sagaId.value in stoppedSagas
            SagaActionView(
                sagaId = row.sagaId.value,
                actionId = row.actionId.value,
                compensationId = row.compensationId.value,
                resource = row.resourceRef,
                compensationKind = row.kind,
                planFingerprint = row.planFingerprint,
                status = row.status,
                evidence = row.receiptRef,
                phase = standing.phase,
                next = standing.next,
                error = standing.error,
                needsOperator = standing.needsOperator || (sagaStopped && !owesNothing(row.status)),
            )
        }

        return SagaInspectionReport(
            view = SagaRunView(
                runId = runId,
                actions = actions,
                sagas = presentation.sagas,
                outcome = presentation.outcome,
            ),
            origins = rows.map { row ->
                SagaInspectedOrigin(
                    sagaId = row.sagaId.value,
                    actionId = row.actionId.value,
                    compensationId = row.compensationId.value,
                    origin = SagaPromiseOrigin(
                        pluginId = row.ownerPluginId,
                        intentVersion = row.intentVersion,
                    ),
                )
            },
        )
    }

    /**
     * Whether the promise owes nothing any more.
     *
     * A promise that was never tried owes nothing: its effect never happened, so there is
     * nothing to undo and nobody has to decide anything. A promise whose effect was kept, never
     * happened, or was undone owes nothing either. Everything else is either owed or in doubt,
     * and a saga that stopped leaves it that way.
     */
    private fun owesNothing(status: CompensationObligationStatus): Boolean =
        status == CompensationObligationStatus.ARMED ||
            status == CompensationObligationStatus.EFFECT_KEPT ||
            status == CompensationObligationStatus.EFFECT_ABSENT ||
            status == CompensationObligationStatus.COMPENSATED
}
