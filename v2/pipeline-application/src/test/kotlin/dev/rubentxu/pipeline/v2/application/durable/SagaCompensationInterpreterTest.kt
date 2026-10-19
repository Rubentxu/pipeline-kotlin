package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDeclaration
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationObligationStatus
import dev.rubentxu.pipeline.v2.domain.saga.CompensationPolicy
import dev.rubentxu.pipeline.v2.domain.saga.EffectEvidence
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.SqliteConnectionFactory
import dev.rubentxu.pipeline.v2.events.durable.SqliteSagaObligationStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * The engine's compensation loop, exercised against a real SQLite file.
 *
 * The file is real because the loop's whole claim is about DURABLE state: it decides on what
 * is on disk, it writes there before and after every body, and a later process would have to
 * reach the same conclusion from the same rows.
 */
class SagaCompensationInterpreterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): String = tempDir.resolve("run.sqlite").toString()

    private fun store() = SqliteSagaObligationStore(
        connectionFactory = { SqliteConnectionFactory.open(dbPath()) },
        dbPath = dbPath(),
    )

    private fun planOperations(leaseEpoch: Long = 1L) = SagaPlanOperationsAdapter(
        runId = "run-1",
        leaseEpoch = leaseEpoch,
        store = store(),
        clock = FixedClock,
    )

    /**
     * The intent port of one action, as a plugin would hold it.
     */
    private fun intentPort(actionId: String, leaseEpoch: Long) = SagaCompensationOperations(
        runId = "run-1",
        forwardOpId = "run-1|stage-0|step-$actionId",
        leaseEpoch = leaseEpoch,
        store = store(),
        eventSink = NullEventSink,
        clock = FixedClock,
    )

    private fun declarationOf(actionId: String, compensationId: String) = CompensationDeclaration(
        sagaId = SAGA,
        actionId = SagaActionId.of(actionId),
        compensationId = CompensationId.of(compensationId),
        planFingerprint = PLAN_A,
        resourceRef = ResourceRef.of("resource/$actionId"),
        kind = CompensationKind.RESTORE,
    )

    /**
     * Arms a promise and reports that its forward effect happened.
     *
     * The arm goes through the INTENT port and the report through the PLAN port, so the
     * obligation the loop compensates is the one a plugin would really have made.
     *
     * The epoch is the lease of the process that owns the run: arming at a newer epoch is what
     * a takeover looks like from the store's point of view.
     */
    private fun armAndConfirm(actionId: String, compensationId: String, leaseEpoch: Long = 1L) {
        val declaration = declarationOf(actionId, compensationId)
        assertInstanceOf<CompensationArmOutcome.Armed>(
            intentPort(actionId, leaseEpoch).arm(declaration),
        )
        planOperations(leaseEpoch).recordForward(
            declaration.key,
            EffectEvidence.Confirmed(EffectReceiptRef.of("rcpt-$actionId")),
        )
    }

    /**
     * Arms a promise WITHOUT saying anything about the forward effect.
     *
     * This is what the process that took the run over does first: it arms its own promises,
     * and its newer epoch becomes the run's high-water mark for everybody else.
     */
    private fun armOnly(actionId: String, compensationId: String, leaseEpoch: Long) {
        assertInstanceOf<CompensationArmOutcome.Armed>(
            intentPort(actionId, leaseEpoch).arm(declarationOf(actionId, compensationId)),
        )
    }

    private fun planOf(vararg actions: SagaActionPlan) = SagaPlan(
        sagaId = SAGA,
        policy = CompensationPolicy.NotReconcilable,
        actions = actions.toList(),
    )

    private fun actionPlan(actionId: String, compensateSlot: String) = SagaActionPlan(
        actionId = SagaActionId.of(actionId),
        compensationId = CompensationId.of("undo-$actionId"),
        resourceRef = ResourceRef.of("resource/$actionId"),
        kind = CompensationKind.RESTORE,
        forwardSlot = PluginStepId("forward-$actionId"),
        compensateSlot = PluginStepId(compensateSlot),
    )

    @Test
    fun `the NEWEST owed obligation is compensated first`() {
        armAndConfirm("reserve", "release")
        armAndConfirm("charge", "refund")
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            SagaCompensationInterpreter(planOperations(), slots).compensate(
                planOf(
                    actionPlan("reserve", "compensate-reserve"),
                    actionPlan("charge", "compensate-charge"),
                ),
            )
        }

        // LIFO: the newest effect is undone first.
        assertEquals(
            listOf("charge", "reserve"),
            report.compensated.map { it.value },
        )
        assertEquals(
            listOf("compensate-charge", "compensate-reserve"),
            slots.invoked.map { it.value },
        )
    }

    @Test
    fun `the obligation is marked as in flight BEFORE its body runs`() {
        armAndConfirm("reserve", "release")
        var statusDuringTheBody: CompensationObligationStatus? = null
        val slots = RecordingSlotInvoker { slot ->
            statusDuringTheBody = store().find("run-1", KEY_OF_RESERVE)?.status
        }

        runBlocking {
            SagaCompensationInterpreter(planOperations(), slots).compensate(
                planOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        // The write-ahead: a crash while the body is in flight must leave a row that says so.
        assertEquals(CompensationObligationStatus.COMPENSATION_PENDING, statusDuringTheBody)
        assertEquals(
            CompensationObligationStatus.COMPENSATED,
            store().find("run-1", KEY_OF_RESERVE)?.status,
        )
    }

    @Test
    fun `a failed compensation stops the Saga and leaves the older one owed`() {
        armAndConfirm("reserve", "release")
        armAndConfirm("charge", "refund")
        val slots = RecordingSlotInvoker(
            outcomes = mapOf(
                PluginStepId("compensate-charge") to BodyOutcome.Completed(
                    StepOutcome.Failure(
                        dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                            kind = dev.rubentxu.pipeline.v2.domain.FailureKind.NETWORK,
                            message = "the provider refused the refund",
                        ),
                    ),
                ),
            ),
        )

        val report = runBlocking {
            SagaCompensationInterpreter(planOperations(), slots).compensate(
                planOf(
                    actionPlan("reserve", "compensate-reserve"),
                    actionPlan("charge", "compensate-charge"),
                ),
            )
        }

        // The Saga stops at the first compensation that did not work: the older one is NOT
        // attempted, because it would run against a state the newer one did not restore.
        assertEquals(listOf("charge"), report.compensated.map { it.value })
        val directive = assertInstanceOf<CompensationDirective.NeedsIntervention>(report.directive)
        assertEquals(InterventionReason.COMPENSATION_FAILED, directive.reason)
        assertEquals(
            CompensationObligationStatus.EFFECT_CONFIRMED,
            store().find("run-1", KEY_OF_RESERVE)?.status,
        )
    }

    @Test
    fun `a Saga that lost the lease does not compensate at all`() {
        // The promise was armed and confirmed by the process that owned the run back then.
        armAndConfirm("reserve", "release", leaseEpoch = 2L)
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            // This process is behind the run's high-water mark: it lost the run to a takeover.
            SagaCompensationInterpreter(planOperations(leaseEpoch = 1L), slots).compensate(
                planOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        // The write-ahead was refused, so the body never left the process: the resource was NOT
        // touched, and the obligation is still owed for whoever owns the run now.
        assertEquals(emptyList<PluginStepId>(), slots.invoked)
        assertEquals(emptyList<SagaActionId>(), report.compensated)
        val directive = assertInstanceOf<CompensationDirective.NeedsIntervention>(report.directive)
        assertEquals(InterventionReason.COMPENSATION_NOT_DISPATCHED, directive.reason)
        assertEquals(
            CompensationObligationStatus.EFFECT_CONFIRMED,
            store().find("run-1", KEY_OF_RESERVE)?.status,
        )
    }

    @Test
    fun `a compensation that cannot be recorded is not claimed as compensated`() {
        armAndConfirm("reserve", "release")
        val slots = RecordingSlotInvoker { _ ->
            // While the body is in flight, another process takes the run over.
            armOnly("charge", "refund", leaseEpoch = 2L)
        }

        val report = runBlocking {
            SagaCompensationInterpreter(planOperations(), slots).compensate(
                planOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        // The body ran, but the answer could not be written down: the row still says the
        // compensation is in flight, so the Saga cannot claim it is settled — and it does not
        // compensate an older obligation against a state nobody proved was restored.
        assertEquals(emptyList<SagaActionId>(), report.compensated)
        val directive = assertInstanceOf<CompensationDirective.NeedsIntervention>(report.directive)
        assertEquals(InterventionReason.COMPENSATION_UNCERTAIN, directive.reason)
        assertEquals(
            CompensationObligationStatus.COMPENSATION_PENDING,
            store().find("run-1", KEY_OF_RESERVE)?.status,
        )
    }

    @Test
    fun `a Saga with nothing owed settles without dispatching a single body`() {
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            SagaCompensationInterpreter(planOperations(), slots).compensate(
                planOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        assertEquals(emptyList<SagaActionId>(), report.compensated)
        assertEquals(emptyList<PluginStepId>(), slots.invoked)
    }

    /**
     * The slot invoker, recorded.
     *
     * It answers with whatever the test scripted and remembers the order in which the bodies
     * were asked for: that order IS the compensation order, and it is the thing worth seeing.
     */
    private class RecordingSlotInvoker(
        private val outcomes: Map<PluginStepId, BodyOutcome> = emptyMap(),
        private val onInvoke: (PluginStepId) -> Unit = {},
    ) : BodySlotInvoker {
        val invoked = mutableListOf<PluginStepId>()

        override suspend fun invoke(
            slot: PluginStepId,
            context: BodyInvocationContext,
        ): BodyOutcome {
            invoked += slot
            onInvoke(slot)
            return outcomes[slot] ?: BodyOutcome.Completed(StepOutcome.Success)
        }
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }

    private companion object {
        val SAGA = SagaId.of("checkout")
        val PLAN_A = SagaPlanFingerprint("a".repeat(64))
        val KEY_OF_RESERVE = dev.rubentxu.pipeline.v2.domain.saga.CompensationKey(
            sagaId = SAGA,
            actionId = SagaActionId.of("reserve"),
            compensationId = CompensationId.of("release"),
        )
    }
}
