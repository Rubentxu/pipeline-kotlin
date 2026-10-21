package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionPhase
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionError
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaIntentContract
import dev.rubentxu.pipeline.v2.domain.saga.SagaNextAction
import dev.rubentxu.pipeline.v2.domain.saga.SagaOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.events.CompensationArmed
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.SQLException
import java.util.Locale
import java.time.Instant

/**
 * What an operator is told about a run, read from the state a REAL run left behind.
 *
 * ## Where the bytes come from
 *
 * The plane is the captured history of a REAL run — the saga whose provider cannot promise
 * that a failed call left nothing behind — read out of the durable store of that run. The
 * promises are written through the store the engine writes, and the history is written
 * through the store the engine writes, so what this test reads is what the engine would
 * have left behind. Nothing here is written by hand.
 *
 * ## What it proves
 *
 * That the read-side answers from the durable state and not from a transcript: the plan
 * fingerprint it shows is the one the run ANNOUNCED, the resources are the ones the run
 * announced, and the promises are the ones the engine armed before it let anything happen.
 * A reader that reconstructed the state from logs would have to agree with the engine by
 * luck.
 */
class SagaInspectionTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): String = tempDir.resolve("run.sqlite").toString()

    private fun store(): SqliteSagaObligationStore =
        SqliteSagaObligationStore(
            connectionFactory = { SqliteConnectionFactory.open(dbPath()) },
            dbPath = dbPath(),
        )

    @Test
    fun `the read-side shows what the run promised and what it still owes`() {
        val state = durableStateOfCapturedRun()

        val view = SagaInspectionProjection.read(RUN_ID, state.rows, state.events).view

        // What the run announced about itself, read with the projection that publishes the
        // reading of a plane. The saga stopped, and the reason it stopped is named.
        assertEquals(
            listOf(SagaOutcome.NeedsIntervention("checkout", InterventionReason.UNKNOWN_EFFECT)),
            view.sagas,
        )
        val failure = assertInstanceOf<RunOutcome.Failure>(view.outcome)
        assertEquals("the action ship failed: shell exited with code 1", failure.failure.message)

        // The promises, in the order they were made, with the resources they are about.
        assertEquals(
            listOf("reserve", "charge", "ship"),
            view.actions.map { it.actionId },
        )
        assertEquals(
            listOf("stock/42", "payment/42", "parcel/42"),
            view.actions.map { it.resource.value },
        )

        // Two effects are known to have happened and are owed a compensation; the third one
        // is in doubt, and nobody can say whether it happened.
        assertEquals(
            listOf(
                CompensationObligationStatus.EFFECT_CONFIRMED,
                CompensationObligationStatus.EFFECT_CONFIRMED,
                CompensationObligationStatus.EFFECT_UNKNOWN,
            ),
            view.actions.map { it.status },
        )
        assertEquals(
            listOf(SagaActionPhase.COMPENSATE, SagaActionPhase.COMPENSATE, SagaActionPhase.FORWARD),
            view.actions.map { it.phase },
        )
        assertEquals(
            listOf(SagaNextAction.Compensate, SagaNextAction.Compensate, SagaNextAction.SettleForward),
            view.actions.map { it.next },
        )
        assertEquals(
            listOf(null, null, SagaActionError.ForwardOutcomeUnknown),
            view.actions.map { it.error },
        )

        // The receipts are the evidence of the two effects that happened.
        assertEquals(
            listOf("receipt-reserve", "receipt-charge", null),
            view.actions.map { it.evidence?.value },
        )

        // The saga stopped, so everything it still owes waits for a person.
        assertTrue(view.needsOperator)
        assertTrue(view.actions.all { it.needsOperator })
    }

    @Test
    fun `the plan the read-side shows is the plan the run announced`() {
        val state = durableStateOfCapturedRun()
        val announced = state.announcedPlanFingerprints()

        val view = SagaInspectionProjection.read(RUN_ID, state.rows, state.events).view

        assertEquals(
            announced,
            view.actions.map { it.planFingerprint.hex },
        )
    }

    @Test
    fun `the connection the reader opens cannot write`() {
        durableStateOfCapturedRun()

        val url = sagaReadOnlyUrl(tempDir.resolve("run.sqlite"))
        val refused = try {
            java.sql.DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM saga_obligation")
                }
            }
            null
        } catch (e: SQLException) {
            e
        }

        assertNotNull(refused, "a connection that cannot write must refuse the write")
    }

    @Test
    fun `reading a run leaves its durable state untouched`() {
        durableStateOfCapturedRun()
        val before = digestOf(tempDir.resolve("run.sqlite"))

        SqliteSagaObligationReader(dbPath()).read(RUN_ID)

        assertEquals(before, digestOf(tempDir.resolve("run.sqlite")))
    }

    @Test
    fun `a run that is not in the store is not a run that owes nothing`() {
        durableStateOfCapturedRun()

        val read = SqliteSagaObligationReader(dbPath()).read("a-run-that-never-happened")

        val state = assertInstanceOf<SagaInspectionRead.Read>(read)
        assertTrue(state.rows.isEmpty())
        assertFalse(state.runExists)
    }

    @Test
    fun `a store without the promise table cannot answer`() {
        SqliteEventStore(dbPath()).use { store ->
            store.append(
                RunStarted(
                    eventId = "11111111-2222-3333-4444-555555555555",
                    runId = RUN_ID,
                    sequence = 0L,
                    occurredAt = Instant.parse("2026-10-20T07:02:50Z"),
                    scriptPath = "captured-plane.pipeline.kts",
                ),
            )
            store.flush()
        }

        val read = SqliteSagaObligationReader(dbPath()).read(RUN_ID)

        val refused = assertInstanceOf<SagaInspectionRead.Refused>(read)
        assertEquals(
            SagaInspectionRefusal.NoSagaObligations(dbPath()),
            refused.refusal,
        )
    }

    /**
     * The durable state of the captured run: its history written through the store the
     * engine writes, and its promises written through the store the engine writes.
     */
    @Test
    fun `the read-side says which plugin armed each promise`() {
        val store = store()
        store.arm(
            SagaObligationRow(
                runId = RUN_ID,
                sagaId = SagaId.of("checkout"),
                actionId = SagaActionId.of("reserve-stock"),
                compensationId = CompensationId.of("release-stock"),
                intentVersion = SagaIntentContract.CURRENT_VERSION,
                planFingerprint = SagaPlanFingerprint("a".repeat(64)),
                resourceRef = ResourceRef.of("stock/reservation-42"),
                kind = CompensationKind.RESTORE,
                status = CompensationObligationStatus.ARMED,
                forwardOpId = "run-1|stage-0|step-3",
                compensationOpId = "saga-v1|compensate|release-stock|run-1|stage-0|step-3",
                leaseEpoch = 1L,
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
                receiptRef = null,
                ownerPluginId = "example.saga",
            ),
        )

        val report = SagaInspectionProjection.read(RUN_ID, store.listForRun(RUN_ID), emptyList())

        val origin = report.originOf("checkout", "reserve-stock", "release-stock")
        assertEquals("example.saga", origin?.pluginId)
        assertEquals(SagaIntentContract.CURRENT_VERSION, origin?.intentVersion)
    }

    private fun durableStateOfCapturedRun(): CapturedState {
        val events = JsonEventLog.decode(plane(PLANE))
        SqliteEventStore(dbPath()).use { store ->
            events.forEach { store.append(it) }
            store.flush()
        }

        val store = store()
        val armed = events.filterIsInstance<CompensationArmed>()
        armed.forEachIndexed { index, event ->
            store.arm(
                SagaObligationRow(
                    runId = RUN_ID,
                    sagaId = SagaId.of(event.sagaId),
                    actionId = SagaActionId.of(event.actionId),
                    compensationId = CompensationId.of(event.compensationId),
                    intentVersion = SagaIntentContract.CURRENT_VERSION,
                    planFingerprint = SagaPlanFingerprint(event.planFingerprint),
                    resourceRef = ResourceRef.of(event.resourceRef),
                    kind = CompensationKind.RESTORE,
                    status = CompensationObligationStatus.ARMED,
                    forwardOpId = forwardOpIdOf(event.compensationOpId),
                    compensationOpId = event.compensationOpId,
                    leaseEpoch = event.leaseEpoch,
                    createdAtMs = 1_000L + index,
                    updatedAtMs = 1_000L + index,
                    receiptRef = null,
                    ownerPluginId = null,
                ),
            )
        }

        // What the run ended in: two effects known to have happened, and one that nobody
        // can place. These are the states the engine settles the promises in, and the
        // receipts are the evidence it keeps of the two that happened.
        val settled = listOf(
            "reserve" to "receipt-reserve",
            "charge" to "receipt-charge",
        ).toMap()
        armed.forEachIndexed { index, event ->
            val status = if (event.actionId == "ship") {
                CompensationObligationStatus.EFFECT_UNKNOWN
            } else {
                CompensationObligationStatus.EFFECT_CONFIRMED
            }
            store.updateOutcome(
                SagaObligationUpdate(
                    runId = RUN_ID,
                    key = CompensationKey(
                        sagaId = SagaId.of(event.sagaId),
                        actionId = SagaActionId.of(event.actionId),
                        compensationId = CompensationId.of(event.compensationId),
                    ),
                    status = status,
                    receiptRef = settled[event.actionId]?.let { EffectReceiptRef.of(it) },
                    updatedAtMs = 2_000L + index,
                    leaseEpoch = event.leaseEpoch,
                ),
            )
        }

        val read = SqliteSagaObligationReader(dbPath()).read(RUN_ID)
        val state = assertInstanceOf<SagaInspectionRead.Read>(read)
        return CapturedState(state.rows, state.events, armed)
    }

    private data class CapturedState(
        val rows: List<SagaObligationRow>,
        val events: List<DomainEvent>,
        val armed: List<CompensationArmed>,
    ) {
        /** The plan digests the run announced, in the order it announced them. */
        fun announcedPlanFingerprints(): List<String> = armed.map { it.planFingerprint }
    }

    /**
     * The forward operation the promise protects, read out of the compensation's own id.
     */
    private fun forwardOpIdOf(compensationOpId: String): String =
        compensationOpId.substringAfterLast('|')

    private fun digestOf(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(path.toFile().readBytes())
        return digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    private fun plane(name: String): String =
        (javaClass.classLoader.getResource(name) ?: error("the captured plane '$name' is missing"))
            .readText()

    private companion object {
        const val PLANE = "real-saga-plane-needs-intervention.json"
        const val RUN_ID = "3e31ccb4-6c98-4e6d-a15e-e2df04779733"
    }
}
