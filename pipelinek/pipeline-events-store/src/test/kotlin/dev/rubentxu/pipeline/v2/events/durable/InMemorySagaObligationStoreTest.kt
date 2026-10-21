package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaIntentContract
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * The VOLATILE Saga obligations, exercised against the rules the port promises.
 *
 * ## What this test is, and what it is not
 *
 * It is NOT a double of [SqliteSagaObligationStoreTest], and it does not claim what only the
 * durable store can promise: an obligation that survives the process that wrote it. This store
 * promises nothing of the sort, on purpose.
 *
 * What it DOES claim is the part both stores have to share, because the Saga's algorithm must
 * not depend on where the promise is kept: the same promise armed twice is still the FIRST
 * promise, a writer that lost the run writes nothing, and closing a Saga moves every obligation
 * at once. If the volatile store disagreed with the durable one about any of those, the same
 * script would behave differently with and without `--db` — which is exactly the contradiction
 * this store exists to remove.
 *
 * The durability guarantee has its own test, against a real file, where it belongs.
 */
class InMemorySagaObligationStoreTest {

    @Test
    fun `arming the same key twice keeps the FIRST promise`() {
        val store = InMemorySagaObligationStore()
        val first = row(status = CompensationObligationStatus.ARMED)
        val second = row(status = CompensationObligationStatus.COMPENSATED)

        assertInstanceOf<SagaArmPersistence.Inserted>(store.arm(first))
        val outcome = store.arm(second)

        val existing = assertInstanceOf<SagaArmPersistence.Existing>(outcome)
        assertEquals(first, existing.row)
        assertEquals(first, store.find("run-1", KEY))
    }

    @Test
    fun `a writer whose epoch is behind the run's high-water mark writes nothing`() {
        val store = InMemorySagaObligationStore()
        store.arm(row(leaseEpoch = 7L))

        val outcome = store.arm(row(actionId = SagaActionId.of("charge"), leaseEpoch = 3L))

        val fenced = assertInstanceOf<SagaArmPersistence.Fenced>(outcome)
        assertEquals(7L, fenced.currentEpoch)
        assertNull(store.find("run-1", CompensationKey(SagaId.of("checkout"), SagaActionId.of("charge"), COMPENSATION_ID)))
    }

    @Test
    fun `closing a Saga moves EVERY obligation of it at once`() {
        val store = InMemorySagaObligationStore()
        store.arm(row(actionId = SagaActionId.of("reserve")))
        store.arm(row(actionId = SagaActionId.of("charge")))

        val moved = store.moveAll(
            runId = "run-1",
            sagaId = SagaId.of("checkout"),
            from = CompensationObligationStatus.ARMED,
            to = CompensationObligationStatus.EFFECT_KEPT,
            updatedAtMs = 2_000L,
            leaseEpoch = 1L,
        )

        val applied = assertInstanceOf<SagaBulkMove.Applied>(moved)
        assertEquals(2, applied.moved)
        assertEquals(0, applied.remaining)
        assertEquals(
            listOf(CompensationObligationStatus.EFFECT_KEPT, CompensationObligationStatus.EFFECT_KEPT),
            store.listForRun("run-1").map { it.status },
        )
    }

    private fun row(
        actionId: SagaActionId = SagaActionId.of("reserve"),
        status: CompensationObligationStatus = CompensationObligationStatus.ARMED,
        leaseEpoch: Long = 1L,
        createdAtMs: Long = 1_000L,
    ) = SagaObligationRow(
        runId = "run-1",
        sagaId = SagaId.of("checkout"),
        actionId = actionId,
        compensationId = COMPENSATION_ID,
        intentVersion = SagaIntentContract.CURRENT_VERSION,
        planFingerprint = PLAN_A,
        resourceRef = ResourceRef.of("stock/reservation-42"),
        kind = CompensationKind.RESTORE,
        status = status,
        forwardOpId = "run-1|stage-0|step-3",
        compensationOpId = "saga-v1|compensate|release-stock|run-1|stage-0|step-3",
        leaseEpoch = leaseEpoch,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
        receiptRef = null,
        ownerPluginId = null,
    )

    private companion object {
        val PLAN_A = SagaPlanFingerprint("a".repeat(64))
        val COMPENSATION_ID = CompensationId.of("release-stock")
        val KEY = CompensationKey(
            sagaId = SagaId.of("checkout"),
            actionId = SagaActionId.of("reserve"),
            compensationId = COMPENSATION_ID,
        )
    }
}
