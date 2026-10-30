package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaIntentContract
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.events.CompensationArmed
import dev.rubentxu.pipeline.v2.events.DomainEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the inspection projection says about a run, asked of the PROJECTION alone.
 *
 * ## What this file is for
 *
 * The projection reads two things — the promises the engine wrote and the announcements the
 * run made — and adds nothing of its own: the plan fingerprint it shows is the one the promise
 * carries, and the origin it reports is the plugin the promise names. Both are readings, not
 * decisions, so they are asked here of the projection, with the rows and the announcements in
 * hand.
 *
 * The plane is the captured history of a REAL run, decoded by the decoder the engine ships,
 * and the promises are built from its announcements the way the engine builds them: one row per
 * armed compensation, carrying the fingerprint the announcement carried.
 *
 * ## Where the persistence went
 *
 * The other half of the read-side lives where it belongs: the reader's own test
 * (SagaInspectionTest) asks the DURABLE store for the rows, and asks that a read leaves the
 * file untouched. That is a fact about a file; this file is about what is said about it.
 */
class SagaInspectionProjectionTest {

    @Test
    fun `the plan the read-side shows is the plan the run announced`() {
        val events = planeEvents()
        val armed = events.filterIsInstance<CompensationArmed>()
        val rows = armed.mapIndexed { index, event -> rowOf(event, index) }

        val view = SagaInspectionProjection.read(RUN_ID, rows, events).view

        assertEquals(
            armed.map { it.planFingerprint },
            view.actions.map { it.planFingerprint.hex },
        )
    }

    @Test
    fun `the read-side says which plugin armed each promise`() {
        val armed = planeEvents().filterIsInstance<CompensationArmed>().first()
        val rows = listOf(rowOf(armed, 0, ownerPluginId = "example.saga"))

        val report = SagaInspectionProjection.read(RUN_ID, rows, emptyList())

        val origin = report.originOf(armed.sagaId, armed.actionId, armed.compensationId)
        assertEquals("example.saga", origin?.pluginId)
        assertEquals(SagaIntentContract.CURRENT_VERSION, origin?.intentVersion)
    }

    /**
     * The promise an announcement produced, built the way the engine builds it: the same
     * fingerprint the announcement carried, in the order the promises were made.
     */
    private fun rowOf(
        event: CompensationArmed,
        index: Int,
        ownerPluginId: String? = null,
    ): SagaObligationRow = SagaObligationRow(
        runId = RUN_ID,
        sagaId = SagaId.of(event.sagaId),
        actionId = SagaActionId.of(event.actionId),
        compensationId = CompensationId.of(event.compensationId),
        intentVersion = SagaIntentContract.CURRENT_VERSION,
        planFingerprint = SagaPlanFingerprint(event.planFingerprint),
        resourceRef = ResourceRef.of(event.resourceRef),
        kind = CompensationKind.RESTORE,
        status = CompensationObligationStatus.ARMED,
        forwardOpId = event.compensationOpId.substringAfterLast('|'),
        compensationOpId = event.compensationOpId,
        leaseEpoch = event.leaseEpoch,
        createdAtMs = 1_000L + index,
        updatedAtMs = 1_000L + index,
        receiptRef = null,
        ownerPluginId = ownerPluginId,
    )

    /**
     * The history of the captured plane, decoded by the decoder the engine ships.
     */
    private fun planeEvents(): List<DomainEvent> = JsonEventLog.decode(plane(PLANE))

    private fun plane(name: String): String =
        checkNotNull(javaClass.getResource("/$name")) {
            "the captured plane '$name' is missing from the test resources"
        }.readText()

    private companion object {
        const val PLANE = "real-saga-plane-needs-intervention.json"
        const val RUN_ID = "3e31ccb4-6c98-4e6d-a15e-e2df04779733"
    }
}
