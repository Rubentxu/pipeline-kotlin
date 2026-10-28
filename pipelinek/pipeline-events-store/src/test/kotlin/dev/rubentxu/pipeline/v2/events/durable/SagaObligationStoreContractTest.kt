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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The contract of [SagaObligationStore], executed against BOTH of its implementations.
 *
 * ## What this test is for
 *
 * The port has two implementations: the volatile one that a run without `--db` binds, and the
 * durable one that a run with `--db` binds. They are the SAME promise kept in two places, and
 * the rule they both owe is the one the product states out loud: the storage choice must not
 * select a different execution algorithm, so the same script behaves the same in both worlds.
 *
 * That rule was a CLAIM, and a claim is not a guard. The two implementations had drifted: the
 * durable one makes the arm moment strictly increasing inside a Saga, and the volatile one
 * stored it verbatim, so two promises armed in the same millisecond came back in an order that
 * the durable one never produces. The compensation order IS the reverse of the arm order, so
 * the same script compensated in two different orders depending on a flag. The durable one also
 * refused an intent version it does not honour and the volatile one accepted it, which is a
 * fail-open where the durable path fails closed.
 *
 * ## What it asserts, and what it does not
 *
 * It asserts that the two implementations ANSWER THE SAME. It does not say what the answer is:
 * that is the durable store's own test ([SqliteSagaObligationStoreTest]) and this file's
 * sibling for the volatile one ([InMemorySagaObligationStoreTest]). Between the three, the
 * rules are written once, the answers are pinned once, and the agreement between the two
 * implementations is checked on every run — so a change to one of them that moves it away from
 * the other is red here instead of red in a user's run.
 *
 * The SQLite file is real on purpose: the durable implementation IS a file, and a double would
 * assert the code's own idea of it instead of the implementation itself.
 */
class SagaObligationStoreContractTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * One rule of the port, and the answer both implementations must give for it.
     *
     * The answer is a canonical text, not the persistence value: the point is that the two
     * stores agree, and a text says so in a message a reader can read.
     */
    private class Rule(
        val title: String,
        val answer: (SagaObligationStore) -> String,
    )

    @TestFactory
    fun `the two implementations of the port answer the same`(): List<DynamicTest> =
        rules().mapIndexed { index, rule ->
            DynamicTest.dynamicTest(rule.title) {
                val volatile = rule.answer(InMemorySagaObligationStore())
                val durable = rule.answer(durableStore(index))
                assertEquals(
                    durable,
                    volatile,
                    "the volatile store answered differently than the durable one",
                )
            }
        }

    private fun durableStore(index: Int): SqliteSagaObligationStore {
        val path = tempDir.resolve("contract-$index.sqlite").toString()
        return SqliteSagaObligationStore(
            connectionFactory = { SqliteConnectionFactory.open(path) },
            dbPath = path,
        )
    }

    /**
     * The rules of the port, as questions both implementations answer.
     *
     * Every entry is a question the two stores must answer the same way, and most of them are
     * questions about a moment where the two used to differ.
     */
    private fun rules(): List<Rule> = listOf(
        Rule("a stale writer asking about a promise that exists finds that promise") { store ->
            store.arm(row(leaseEpoch = 5L))
            armAnswer(store.arm(row(leaseEpoch = 2L)))
        },
        Rule("a stale writer asking about a promise that does not exist is fenced out") { store ->
            store.arm(row(leaseEpoch = 5L))
            armAnswer(store.arm(row(leaseEpoch = 2L).copy(compensationId = CompensationId.of("refund"))))
        },
        Rule("two promises armed in the same millisecond keep the order they were armed in") { store ->
            store.arm(row(createdAtMs = 1_000L).copy(compensationId = CompensationId.of("zebra")))
            store.arm(row(createdAtMs = 1_000L).copy(compensationId = CompensationId.of("alpha")))
            store.listForRun("run-1").joinToString(",") { it.compensationId.value }
        },
        Rule("the arm moment of two promises of the same Saga is strictly increasing") { store ->
            store.arm(row(createdAtMs = 1_000L).copy(actionId = SagaActionId.of("reserve")))
            store.arm(row(createdAtMs = 1_000L).copy(actionId = SagaActionId.of("charge")))
            store.listForRun("run-1").joinToString(",") { "${it.actionId.value}@${it.createdAtMs}" }
        },
        Rule("the promises of a run come back in a defined order") { store ->
            store.arm(row(sagaId = SagaId.of("shipping"), createdAtMs = 1_000L))
            store.arm(row(sagaId = SagaId.of("checkout"), createdAtMs = 1_000L))
            store.listForRun("run-1").joinToString(",") { it.sagaId.value }
        },
        Rule("arming a promise whose intent version is not honoured is refused") { store ->
            refused { armAnswer(store.arm(row(intentVersion = 0))) }
        },
        Rule("arming a promise with a blank run id is refused") { store ->
            refused { armAnswer(store.arm(row(runId = " "))) }
        },
        Rule("arming a promise with a negative epoch is refused") { store ->
            refused { armAnswer(store.arm(row(leaseEpoch = -1L))) }
        },
        Rule("updating a promise that was never armed answers NotFound") { store ->
            updateAnswer(store.updateOutcome(update(status = CompensationObligationStatus.EFFECT_CONFIRMED)))
        },
        Rule("a writer behind the epoch is fenced out of the update") { store ->
            store.arm(row(leaseEpoch = 5L))
            updateAnswer(store.updateOutcome(update(leaseEpoch = 2L)))
        },
        Rule("a writer that names another plugin is refused") { store ->
            store.arm(row(ownerPluginId = "plugin-a"))
            updateAnswer(store.updateOutcome(update(ownerPluginId = "plugin-b")))
        },
        Rule("a writer that names nobody may write a promise owned by a plugin") { store ->
            store.arm(row(ownerPluginId = "plugin-a"))
            updateAnswer(store.updateOutcome(update(ownerPluginId = null)))
        },
        Rule("moving a Saga behind the epoch answers Fenced") { store ->
            store.arm(row(leaseEpoch = 5L))
            moveAnswer(moveAll(store, leaseEpoch = 2L))
        },
        Rule("moving a Saga with nothing in that state answers Applied") { store ->
            store.arm(row(leaseEpoch = 5L))
            moveAnswer(
                moveAll(
                    store,
                    from = CompensationObligationStatus.EFFECT_CONFIRMED,
                    leaseEpoch = 2L,
                ),
            )
        },
        Rule("moving a Saga with a promise of another plugin is refused") { store ->
            store.arm(row(ownerPluginId = "plugin-a"))
            moveAnswer(moveAll(store, ownerPluginId = "plugin-b"))
        },
        Rule("a fenced writer and a promise of another plugin are answered the same way") { store ->
            store.arm(row(leaseEpoch = 5L, ownerPluginId = "plugin-a"))
            moveAnswer(moveAll(store, leaseEpoch = 2L, ownerPluginId = "plugin-b"))
        },
    )

    private fun armAnswer(outcome: SagaArmPersistence): String = when (outcome) {
        is SagaArmPersistence.Inserted -> "Inserted"
        is SagaArmPersistence.Existing -> "Existing"
        is SagaArmPersistence.Fenced -> "Fenced(${outcome.currentEpoch})"
    }

    private fun updateAnswer(outcome: SagaUpdatePersistence): String = when (outcome) {
        is SagaUpdatePersistence.Updated -> "Updated(${outcome.row.status})"
        is SagaUpdatePersistence.NotFound -> "NotFound"
        is SagaUpdatePersistence.ForeignOwner -> "ForeignOwner(${outcome.row.ownerPluginId})"
        is SagaUpdatePersistence.Fenced -> "Fenced(${outcome.currentEpoch})"
    }

    private fun moveAnswer(outcome: SagaBulkMove): String = when (outcome) {
        is SagaBulkMove.Applied -> "Applied(moved=${outcome.moved}, remaining=${outcome.remaining})"
        is SagaBulkMove.ForeignOwner -> "ForeignOwner(${outcome.row.ownerPluginId})"
        is SagaBulkMove.Fenced -> "Fenced(${outcome.currentEpoch})"
    }

    /**
     * The answer of a refusal, as the KIND of refusal and not as its text.
     *
     * The text of a diagnostic is not part of the contract between two stores, but the KIND of
     * refusal is: one store refusing where the other accepts is a different behaviour, and one
     * store refusing with a different message is not.
     */
    private fun refused(what: () -> String): String =
        try {
            what()
        } catch (refusal: IllegalArgumentException) {
            "refused(${refusal.javaClass.simpleName})"
        }

    private fun row(
        runId: String = "run-1",
        sagaId: SagaId = SagaId.of("checkout"),
        actionId: SagaActionId = SagaActionId.of("reserve-stock"),
        intentVersion: Int = SagaIntentContract.CURRENT_VERSION,
        leaseEpoch: Long = 1L,
        createdAtMs: Long = 1_000L,
        ownerPluginId: String? = null,
    ) = SagaObligationRow(
        runId = runId,
        sagaId = sagaId,
        actionId = actionId,
        compensationId = CompensationId.of("release-stock"),
        intentVersion = intentVersion,
        planFingerprint = PLAN_A,
        resourceRef = ResourceRef.of("stock/reservation-42"),
        kind = CompensationKind.RESTORE,
        status = CompensationObligationStatus.ARMED,
        forwardOpId = "run-1|stage-0|step-3",
        compensationOpId = "saga-v1|compensate|release-stock|run-1|stage-0|step-3",
        leaseEpoch = leaseEpoch,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
        receiptRef = null,
        ownerPluginId = ownerPluginId,
    )

    private fun update(
        status: CompensationObligationStatus = CompensationObligationStatus.EFFECT_CONFIRMED,
        leaseEpoch: Long = 1L,
        ownerPluginId: String? = null,
    ) = SagaObligationUpdate(
        runId = "run-1",
        key = CompensationKey(
            sagaId = SagaId.of("checkout"),
            actionId = SagaActionId.of("reserve-stock"),
            compensationId = CompensationId.of("release-stock"),
        ),
        status = status,
        receiptRef = EffectReceiptRef.of("rcpt-1"),
        updatedAtMs = 2_000L,
        leaseEpoch = leaseEpoch,
        ownerPluginId = ownerPluginId,
    )

    private fun moveAll(
        store: SagaObligationStore,
        from: CompensationObligationStatus = CompensationObligationStatus.ARMED,
        leaseEpoch: Long = 1L,
        ownerPluginId: String? = null,
    ) = store.moveAll(
        runId = "run-1",
        sagaId = SagaId.of("checkout"),
        from = from,
        to = CompensationObligationStatus.EFFECT_KEPT,
        updatedAtMs = 3_000L,
        leaseEpoch = leaseEpoch,
        ownerPluginId = ownerPluginId,
    )

    private companion object {
        val PLAN_A = SagaPlanFingerprint("a".repeat(64))
    }
}
