package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaIntentContract
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The durable Saga obligations, exercised against a real SQLite file.
 *
 * The file is real on purpose: the guarantees under test are about what SURVIVES — a second
 * write that must not overwrite the first, a writer that must be fenced out, an obligation that
 * must still be there after the process that wrote it is gone. An in-memory double would
 * assert the code's own idea of those guarantees instead of the guarantees themselves.
 */
class SqliteSagaObligationStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): String = tempDir.resolve("run.sqlite").toString()

    private fun store(): SqliteSagaObligationStore =
        SqliteSagaObligationStore(
            connectionFactory = { SqliteConnectionFactory.open(dbPath()) },
            dbPath = dbPath(),
        )

    private fun row(
        runId: String = "run-1",
        planFingerprint: SagaPlanFingerprint = PLAN_A,
        leaseEpoch: Long = 1L,
        createdAtMs: Long = 1_000L,
    ) = SagaObligationRow(
        runId = runId,
        sagaId = SagaId.of("checkout"),
        actionId = SagaActionId.of("reserve-stock"),
        compensationId = CompensationId.of("release-stock"),
        intentVersion = SagaIntentContract.CURRENT_VERSION,
        planFingerprint = planFingerprint,
        resourceRef = ResourceRef.of("stock/reservation-42"),
        kind = CompensationKind.RESTORE,
        status = CompensationObligationStatus.ARMED,
        forwardOpId = "run-1|stage-0|step-3",
        compensationOpId = "saga-v1|compensate|release-stock|run-1|stage-0|step-3",
        leaseEpoch = leaseEpoch,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
        receiptRef = null,
    )

    @Nested
    inner class Writing {

        @Test
        fun `an armed obligation is written and read back`() {
            val store = store()

            val persistence = store.arm(row())

            val inserted = assertInstanceOf<SagaArmPersistence.Inserted>(persistence)
            assertEquals(PLAN_A, inserted.row.planFingerprint)
            assertEquals("stock/reservation-42", inserted.row.resourceRef.value)
            assertEquals(CompensationObligationStatus.ARMED, inserted.row.status)
        }

        @Test
        fun `arming the same key twice keeps the FIRST promise`() {
            val store = store()
            store.arm(row(createdAtMs = 1_000L))

            val persistence = store.arm(row(createdAtMs = 9_999L))

            val existing = assertInstanceOf<SagaArmPersistence.Existing>(persistence)
            // The second arm must NOT have overwritten the first: the promise that was made is
            // the one that is kept, and its timestamp is the evidence of that.
            assertEquals(1_000L, existing.row.createdAtMs)
        }

        @Test
        fun `a different plan under the same key does not replace the stored one`() {
            val store = store()
            store.arm(row(planFingerprint = PLAN_A))

            store.arm(row(planFingerprint = PLAN_B))

            val stored = store.find("run-1", KEY)
            assertEquals(PLAN_A, stored?.planFingerprint)
        }

        @Test
        fun `a writer behind the run epoch is fenced out`() {
            val store = store()
            // A NEWER writer armed first and moved the run's epoch up.
            store.arm(row(leaseEpoch = 5L))

            // This writer is behind the run's high-water mark and asks for a key that does not
            // exist yet, so nothing but the epoch guard can stop it.
            val staleKey = row(leaseEpoch = 2L).copy(compensationId = CompensationId.of("refund"))
            val persistence = store.arm(staleKey)

            val fenced = assertInstanceOf<SagaArmPersistence.Fenced>(persistence)
            assertEquals(5L, fenced.currentEpoch)
            // And nothing was written under that key.
            assertNull(
                store.find(
                    "run-1",
                    CompensationKey(
                        sagaId = SagaId.of("checkout"),
                        actionId = SagaActionId.of("reserve-stock"),
                        compensationId = CompensationId.of("refund"),
                    ),
                ),
            )
        }

        @Test
        fun `a stale writer asking about an existing key finds the promise, not a new one`() {
            val store = store()
            store.arm(row(leaseEpoch = 5L))

            // The key already exists: the stale writer's write is still refused, and what it
            // gets back is the promise that is already there.
            val persistence = store.arm(row(leaseEpoch = 2L))

            assertInstanceOf<SagaArmPersistence.Existing>(persistence)
        }

        @Test
        fun `a writer at the run epoch may still arm a different key`() {
            val store = store()
            store.arm(row(leaseEpoch = 5L))

            val persistence = store.arm(
                row(leaseEpoch = 5L).copy(compensationId = CompensationId.of("refund")),
            )

            assertInstanceOf<SagaArmPersistence.Inserted>(persistence)
        }

        @Test
        fun `two promises armed in the same millisecond keep the order they were armed in`() {
            val store = store()
            // The SAME millisecond for both: the arm order must not be an accident of the
            // identifiers, because the compensation order is the reverse of the arm order.
            store.arm(row(createdAtMs = 1_000L).copy(compensationId = CompensationId.of("zebra")))
            store.arm(row(createdAtMs = 1_000L).copy(compensationId = CompensationId.of("alpha")))

            val listed = store.listForRun("run-1")

            assertEquals(
                listOf("zebra", "alpha"),
                listed.map { it.compensationId.value },
            )
        }
    }

    @Nested
    inner class Moving {

        @Test
        fun `the obligation moves to the new state and keeps its promise`() {
            val store = store()
            store.arm(row())

            val persistence = store.updateOutcome(
                SagaObligationUpdate(
                    runId = "run-1",
                    key = KEY,
                    status = CompensationObligationStatus.EFFECT_CONFIRMED,
                    receiptRef = EffectReceiptRef.of("rcpt-1"),
                    updatedAtMs = 2_000L,
                    leaseEpoch = 1L,
                ),
            )

            val updated = assertInstanceOf<SagaUpdatePersistence.Updated>(persistence)
            assertEquals(CompensationObligationStatus.EFFECT_CONFIRMED, updated.row.status)
            assertEquals("rcpt-1", updated.row.receiptRef?.value)
            // The promise itself is untouched: it was made at a moment, and moving it does not
            // move the moment it was made.
            assertEquals(1_000L, updated.row.createdAtMs)
            assertEquals(PLAN_A, updated.row.planFingerprint)
        }

        @Test
        fun `the moved state is still there after the writer is gone`() {
            store().arm(row())

            store().updateOutcome(
                SagaObligationUpdate(
                    runId = "run-1",
                    key = KEY,
                    status = CompensationObligationStatus.COMPENSATED,
                    receiptRef = null,
                    updatedAtMs = 2_000L,
                    leaseEpoch = 1L,
                ),
            )

            // A DIFFERENT store instance over the SAME file: this is what a later process,
            // after a restart, sees.
            val later = store().find("run-1", KEY)

            assertEquals(CompensationObligationStatus.COMPENSATED, later?.status)
        }

        @Test
        fun `a key that was never armed stays unpromised`() {
            val store = store()

            val persistence = store.updateOutcome(
                SagaObligationUpdate(
                    runId = "run-1",
                    key = KEY,
                    status = CompensationObligationStatus.COMPENSATED,
                    receiptRef = null,
                    updatedAtMs = 2_000L,
                    leaseEpoch = 1L,
                ),
            )

            assertInstanceOf<SagaUpdatePersistence.NotFound>(persistence)
            assertNull(store.find("run-1", KEY))
        }

        @Test
        fun `a writer behind the run epoch is fenced out of the move too`() {
            val store = store()
            store.arm(row(leaseEpoch = 5L))

            val persistence = store.updateOutcome(
                SagaObligationUpdate(
                    runId = "run-1",
                    key = KEY,
                    status = CompensationObligationStatus.EFFECT_CONFIRMED,
                    receiptRef = null,
                    updatedAtMs = 2_000L,
                    leaseEpoch = 2L,
                ),
            )

            val fenced = assertInstanceOf<SagaUpdatePersistence.Fenced>(persistence)
            assertEquals(5L, fenced.currentEpoch)
            // And the state is exactly where it was: the stale writer wrote nothing.
            assertEquals(
                CompensationObligationStatus.ARMED,
                store.find("run-1", KEY)?.status,
            )
        }
    }

    @Nested
    inner class Reading {

        @Test
        fun `the obligation is still there after the writer is gone`() {
            store().arm(row())

            // A DIFFERENT store instance over the SAME file: this is what a later process,
            // after a restart, sees.
            val later = store()
            val found = later.find("run-1", KEY)

            assertEquals("checkout", found?.sagaId?.value)
            assertEquals("release-stock", found?.compensationId?.value)
            assertEquals(PLAN_A, found?.planFingerprint)
            assertEquals(1L, found?.leaseEpoch)
        }

        @Test
        fun `a key that was never armed is not there`() {
            val store = store()

            assertNull(store.find("run-1", KEY))
        }

        @Test
        fun `the obligations of a run are listed`() {
            val store = store()
            store.arm(row())
            store.arm(row().copy(compensationId = CompensationId.of("refund")))

            val listed = store.listForRun("run-1")

            assertEquals(2, listed.size)
            assertEquals(
                listOf("refund", "release-stock"),
                listed.map { it.compensationId.value }.sorted(),
            )
        }

        @Test
        fun `another run's obligations are not visible`() {
            val store = store()
            store.arm(row(runId = "run-1"))

            assertNull(store.find("run-2", KEY))
            assertEquals(0, store.listForRun("run-2").size)
        }
    }

    private companion object {
        val PLAN_A = SagaPlanFingerprint("a".repeat(64))
        val PLAN_B = SagaPlanFingerprint("b".repeat(64))
        val KEY = CompensationKey(
            sagaId = SagaId.of("checkout"),
            actionId = SagaActionId.of("reserve-stock"),
            compensationId = CompensationId.of("release-stock"),
        )
    }
}
