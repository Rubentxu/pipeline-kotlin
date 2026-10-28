package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDeclaration
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
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
import dev.rubentxu.pipeline.v2.domain.saga.SagaOperationIdentity
import dev.rubentxu.pipeline.v2.domain.saga.SagaOperationPhase
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanOperations
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.ExecutionContextPatch
import dev.rubentxu.pipeline.v2.events.CompensationFinished
import dev.rubentxu.pipeline.v2.events.CompensationStarted
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.durable.InMemorySagaObligationStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * The engine's compensation loop, exercised against the PORT's volatile implementation.
 *
 * ## What this test decides, and what it does not
 *
 * The loop's decisions are about what the promises say: which obligation is compensated first,
 * that a promise is marked in flight BEFORE its body leaves the process, that a lost answer is
 * asked again with the same key, that a Saga which lost the lease compensates nothing, and that
 * a compensation which could not be written down is not claimed as done. Those decisions belong
 * to the interpreter, and the interpreter reads them from the PORT — so this test hands it the
 * port's cheapest implementation, the one that keeps the promises in memory.
 *
 * A real file would have made this a test about the FILE as well. The durability of the
 * promises is guarded elsewhere and better: `SqliteSagaObligationStoreTest` pins the durable
 * store's own answers, `SagaObligationStoreContractTest` pins that the durable and the
 * volatile answers AGREE, and the durable scenarios in `examples/durable/` exercise the whole
 * flow against a real database. What this test guards is the LOOP.
 */
class SagaCompensationInterpreterTest {

    /**
     * The promises of this run, in memory.
     *
     * One instance for the whole test, because the loop's claims are about promises that
     * outlive the call that made them: a promise armed in one step is read back in another.
     * JUnit gives every test method its own instance of this class, so each test starts with
     * an empty store.
     */
    private val store = InMemorySagaObligationStore()

    private fun planOperations(leaseEpoch: Long = 1L) = SagaPlanOperationsAdapter(
        runId = "run-1",
        leaseEpoch = leaseEpoch,
        store = store,
        clock = FixedClock,
    )

    /**
     * The intent port of one action, as a plugin would hold it.
     */
    private fun intentPort(actionId: String, leaseEpoch: Long) = SagaCompensationOperations(
        runId = "run-1",
        forwardOpId = "run-1|stage-0|step-$actionId",
        leaseEpoch = leaseEpoch,
        store = store,
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

    /**
     * The interpreter under test, with the ports it needs.
     *
     * The event sink is a parameter because the EVENTS are part of what the loop owes the
     * outside world: a compensation that started, one that finished, and the Saga's terminal
     * state are facts a reader of the event plane needs.
     *
     * The completion port is the real one, against the same promises: the interpreter does not
     * decide what a closing means, it asks the state and announces the answer.
     */
    private fun interpreter(
        slots: BodySlotInvoker,
        operations: SagaPlanOperations = planOperations(),
        events: EventSink = RecordingEventSink(),
        completion: SagaCompletionOperations = completion(),
    ) = SagaCompensationInterpreter(
        runId = "run-1",
        planOperations = operations,
        completion = completion,
        slotInvoker = slots,
        eventSink = events,
        clock = FixedClock,
        // These tests are about the loop, not about the body the promise names: they arm
        // promises that name no body, so there is nothing to compare and every compensation
        // runs. The body check has its own tests.
        bodies = null,
        promisedBodies = SagaPromisedBodyReader { null },
    )

    /**
     * The engine's side of a Saga's completion, against the same promises as everything else.
     */
    private fun completion(leaseEpoch: Long = 1L) = SagaCompletionAdapter(
        runId = "run-1",
        leaseEpoch = leaseEpoch,
        store = store,
        clock = FixedClock,
    )

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

    /**
     * The plan of a Saga whose lost answers may be settled by ASKING THE PROVIDER.
     *
     * The difference is not cosmetic: a provider that can be asked is a provider that can
     * turn "nobody knows" into "it is done", and that is the only way out of a lost answer
     * that is not a human's job.
     */
    private fun reconcilablePlanOf(vararg actions: SagaActionPlan) = SagaPlan(
        sagaId = SAGA,
        policy = CompensationPolicy.Reconcilable,
        actions = actions.toList(),
    )

    private fun keyOf(actionId: String, compensationId: String) = CompensationKey(
        sagaId = SAGA,
        actionId = SagaActionId.of(actionId),
        compensationId = CompensationId.of(compensationId),
    )

    /**
     * The operation key the engine hands to a body, as the domain derives it.
     *
     * The key identifies the OPERATION and not the attempt, so the same operation asked twice
     * carries the same key — that is what lets a provider recognise "I already did this".
     */
    private fun operationKeyOf(
        actionId: String,
        compensationId: String,
        phase: SagaOperationPhase,
    ): String = SagaOperationIdentity.derive(
        forwardOpId = "run-1|stage-0|step-$actionId",
        phase = phase,
        compensationId = CompensationId.of(compensationId),
    )

    /**
     * A promise whose forward was DISPATCHED and whose answer was LOST.
     *
     * The row says "something may have happened", which is the only honest reading of a crash
     * between the write-ahead and the answer.
     */
    private fun armAndDispatchForward(actionId: String, compensationId: String) {
        armOnly(actionId, compensationId, leaseEpoch = 1L)
        assertInstanceOf<SagaRecordOutcome.Recorded>(
            planOperations().beginForward(keyOf(actionId, compensationId)),
        )
    }

    /**
     * A promise whose COMPENSATION was dispatched and whose answer was lost.
     */
    private fun armAndDispatchCompensation(actionId: String, compensationId: String) {
        armAndConfirm(actionId, compensationId)
        assertInstanceOf<SagaRecordOutcome.Recorded>(
            planOperations().beginCompensation(keyOf(actionId, compensationId)),
        )
    }

    @Test
    fun `a forward whose answer was lost is asked again, with the same key and a new attempt`() {
        armAndDispatchForward("reserve", "release")
        val slots = RecordingSlotInvoker()

        runBlocking {
            interpreter(slots).compensate(
                reconcilablePlanOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        // The FORWARD body is the one that can say whether the effect happened, so it is the
        // one that is asked again. The answer it gives is the FORWARD's own key, because the
        // key identifies the operation and not the attempt: a provider that recognises the
        // key can say "I already did that" instead of doing it twice.
        assertEquals("forward-reserve", slots.invoked.first().value)
        val forward = slots.contexts.first()
        val forwardPatch = assertInstanceOf<ExecutionContextPatch.Environment>(forward.patch)
        assertEquals(
            operationKeyOf("reserve", "release", SagaOperationPhase.FORWARD),
            forwardPatch.values[SagaOperationIdentity.OPERATION_KEY_ENV],
        )
        // A NEW attempt: the first attempt has a row of its own in the operation journal, and
        // two attempts of one operation are two rows, not one row written twice.
        assertEquals(2, forward.attempt?.index)
    }

    @Test
    fun `a compensation whose answer was lost is asked again and the obligation is discharged`() {
        armAndDispatchCompensation("reserve", "release")
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            interpreter(slots).compensate(
                reconcilablePlanOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        // The compensation is the body that can say whether the resource is back, so it is the
        // one that is asked again — with the COMPENSATION's own key, the very key the first
        // attempt used, so a body that already did the work can recognise it and say so.
        assertEquals(listOf("compensate-reserve"), slots.invoked.map { it.value })
        val context = slots.contexts.single()
        val patch = assertInstanceOf<ExecutionContextPatch.Environment>(context.patch)
        assertEquals(
            operationKeyOf("reserve", "release", SagaOperationPhase.COMPENSATE),
            patch.values[SagaOperationIdentity.OPERATION_KEY_ENV],
        )
        assertEquals(2, context.attempt?.index)

        // And the answer is written down: the obligation is discharged and the Saga settles.
        assertEquals(
            CompensationObligationStatus.COMPENSATED,
            store.find("run-1", KEY_OF_RESERVE)?.status,
        )
        assertInstanceOf<CompensationDirective.Settled>(report.directive)
    }

    @Test
    fun `a provider that cannot answer is asked once, and then a human decides`() {
        armAndDispatchForward("reserve", "release")
        val slots = RecordingSlotInvoker(
            outcomes = mapOf(
                PluginStepId("forward-reserve") to BodyOutcome.Completed(StepOutcome.Unstable),
            ),
        )

        val report = runBlocking {
            interpreter(slots).compensate(
                reconcilablePlanOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        // One re-ask, and no more: a provider that cannot answer is a human's job, and a loop
        // that keeps asking an obligation that will never settle is a hang, not a policy.
        assertEquals(listOf("forward-reserve"), slots.invoked.map { it.value })
        val directive = assertInstanceOf<CompensationDirective.NeedsIntervention>(report.directive)
        assertEquals(InterventionReason.UNKNOWN_EFFECT, directive.reason)
    }

    @Test
    fun `the NEWEST owed obligation is compensated first`() {
        armAndConfirm("reserve", "release")
        armAndConfirm("charge", "refund")
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            interpreter(slots).compensate(
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
            statusDuringTheBody = store.find("run-1", KEY_OF_RESERVE)?.status
        }

        runBlocking {
            interpreter(slots).compensate(
                planOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        // The write-ahead: a crash while the body is in flight must leave a row that says so.
        assertEquals(CompensationObligationStatus.COMPENSATION_PENDING, statusDuringTheBody)
        assertEquals(
            CompensationObligationStatus.COMPENSATED,
            store.find("run-1", KEY_OF_RESERVE)?.status,
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
            interpreter(slots).compensate(
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
            store.find("run-1", KEY_OF_RESERVE)?.status,
        )
    }

    @Test
    fun `the loop announces what it did, in order`() {
        armAndConfirm("reserve", "release")
        armAndConfirm("charge", "refund")
        val events = RecordingEventSink()
        val slots = RecordingSlotInvoker()

        runBlocking {
            interpreter(slots, events = events).compensate(
                planOf(
                    actionPlan("reserve", "compensate-reserve"),
                    actionPlan("charge", "compensate-charge"),
                ),
            )
        }

        // Two facts per compensation and one for the Saga: what started, what finished, and
        // how the Saga ended. The order is the order in which they happened.
        assertEquals(
            listOf(
                "CompensationStarted", "CompensationFinished",
                "CompensationStarted", "CompensationFinished",
                "SagaTerminal",
            ),
            events.events.map { it.kind },
        )

        val started = assertInstanceOf<CompensationStarted>(events.events[0])
        assertEquals("checkout", started.sagaId)
        assertEquals("charge", started.actionId)
        // The SLOT is the join with the journal: it is the plugin step key of the body that
        // ran, and the newest effect is the first one undone.
        assertEquals("compensate-charge", started.slot)

        val finished = assertInstanceOf<CompensationFinished>(events.events[1])
        assertEquals("CONFIRMED", finished.outcome)
        assertEquals("compensate-charge", finished.slot)

        val terminal = assertInstanceOf<SagaTerminal>(events.events[4])
        assertEquals("SETTLED", terminal.state)
        // The Saga says WHICH actions it compensated, and in WHICH order.
        assertEquals(listOf("charge", "reserve"), terminal.compensatedActions)
    }

    @Test
    fun `the compensation is announced BEFORE its body runs`() {
        armAndConfirm("reserve", "release")
        val events = RecordingEventSink()
        var eventsWhenTheBodyRan = -1
        val slots = RecordingSlotInvoker { _ -> eventsWhenTheBodyRan = events.events.size }

        runBlocking {
            interpreter(slots, events = events).compensate(
                planOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        // The announcement of a compensation that is ABOUT to run is what makes a crash in
        // the body visible: a reader that finds a start with no finish knows the answer was
        // lost. An announcement that came AFTER the body would leave a crash in the body
        // with nothing at all to show for it.
        assertEquals(1, eventsWhenTheBodyRan)
        assertEquals("CompensationStarted", events.events[0].kind)
    }

    @Test
    fun `a compensation that was not dispatched is announced as such, and no body runs`() {
        armAndConfirm("reserve", "release", leaseEpoch = 2L)
        val events = RecordingEventSink()
        val slots = RecordingSlotInvoker()

        runBlocking {
            interpreter(
                slots,
                operations = planOperations(leaseEpoch = 1L),
                events = events,
            ).compensate(planOf(actionPlan("reserve", "compensate-reserve")))
        }

        // The body never ran, so there is nothing to announce about it: what the reader gets
        // is the terminal state and the reason. An announcement of a compensation that never
        // happened would be a lie in the only log a reader may not have.
        assertEquals(listOf("SagaTerminal"), events.events.map { it.kind })
        val terminal = assertInstanceOf<SagaTerminal>(events.events[0])
        assertEquals("NEEDS_INTERVENTION", terminal.state)
        assertEquals("COMPENSATION_NOT_DISPATCHED", terminal.reason)
        assertEquals(emptyList<String>(), terminal.compensatedActions)
    }

    @Test
    fun `a Saga that lost the lease does not compensate at all`() {
        // The promise was armed and confirmed by the process that owned the run back then.
        armAndConfirm("reserve", "release", leaseEpoch = 2L)
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            // This process is behind the run's high-water mark: it lost the run to a takeover.
            interpreter(slots, operations = planOperations(leaseEpoch = 1L)).compensate(
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
            store.find("run-1", KEY_OF_RESERVE)?.status,
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
            interpreter(slots).compensate(
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
            store.find("run-1", KEY_OF_RESERVE)?.status,
        )
    }

    @Test
    fun `a Saga with nothing owed settles without dispatching a single body`() {
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            interpreter(slots).compensate(
                planOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        assertEquals(emptyList<SagaActionId>(), report.compensated)
        assertEquals(emptyList<PluginStepId>(), slots.invoked)
    }

    @Test
    fun `a Saga that ended well keeps its effects, and says so`() {
        armAndConfirm("reserve", "release")
        val events = RecordingEventSink()
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            interpreter(slots, events = events).complete(
                planOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        // The effect happened and the Saga succeeded, so the promise is void: the obligation is
        // KEPT, not compensated, and nothing is owed afterwards.
        assertEquals(
            CompensationObligationStatus.EFFECT_KEPT,
            store.find("run-1", KEY_OF_RESERVE)?.status,
        )
        assertEquals(listOf("reserve"), report.kept.map { it.value })
        assertInstanceOf<CompensationDirective.Settled>(report.directive)

        // No body ran: keeping an effect is not undoing it, so there is nothing to dispatch.
        assertEquals(emptyList<PluginStepId>(), slots.invoked)

        // And the plane says it: a Saga that settled, compensating nothing.
        assertEquals(listOf("SagaTerminal"), events.events.map { it.kind })
        val terminal = assertInstanceOf<SagaTerminal>(events.events[0])
        assertEquals("SETTLED", terminal.state)
        assertEquals(emptyList<String>(), terminal.compensatedActions)
    }

    @Test
    fun `a Saga that was already compensated cannot be completed`() {
        // The chain failed and the engine undid the effect. Completing it now would claim a
        // Saga whose resources are gone.
        armAndConfirm("reserve", "release")
        runBlocking {
            interpreter(RecordingSlotInvoker()).compensate(
                planOf(actionPlan("reserve", "compensate-reserve")),
            )
        }
        val events = RecordingEventSink()

        val report = runBlocking {
            interpreter(RecordingSlotInvoker(), events = events).complete(
                planOf(actionPlan("reserve", "compensate-reserve")),
            )
        }

        val directive = assertInstanceOf<CompensationDirective.NeedsIntervention>(report.directive)
        assertEquals(InterventionReason.SAGA_ALREADY_COMPENSATED, directive.reason)
        // The row still says the effect was undone: a refused completion does not rewrite it.
        assertEquals(
            CompensationObligationStatus.COMPENSATED,
            store.find("run-1", KEY_OF_RESERVE)?.status,
        )
        val terminal = assertInstanceOf<SagaTerminal>(events.events.last())
        assertEquals("NEEDS_INTERVENTION", terminal.state)
        assertEquals("SAGA_ALREADY_COMPENSATED", terminal.reason)
    }

    @Test
    fun `a completion that lost the lease keeps nothing and claims nothing`() {
        // The promise was armed and confirmed by the process that owned the run back then.
        armAndConfirm("reserve", "release", leaseEpoch = 2L)

        val report = runBlocking {
            // This process is behind the run's high-water mark: it lost the run to a takeover.
            interpreter(
                RecordingSlotInvoker(),
                completion = completion(leaseEpoch = 1L),
            ).complete(planOf(actionPlan("reserve", "compensate-reserve")))
        }

        // The write was refused, so the effect is still owed: the Saga is open, and saying it
        // was kept would be a claim nobody can back.
        assertEquals(
            CompensationObligationStatus.EFFECT_CONFIRMED,
            store.find("run-1", KEY_OF_RESERVE)?.status,
        )
        assertEquals(emptyList<SagaActionId>(), report.kept)
        val directive = assertInstanceOf<CompensationDirective.NeedsIntervention>(report.directive)
        assertEquals(InterventionReason.COMPLETION_NOT_RECORDED, directive.reason)
    }

    /**
     * The slot invoker, recorded.
     *
     * It answers with whatever the test scripted and remembers the order in which the bodies
     * were asked for: that order IS the compensation order, and it is the thing worth seeing.
     * It also remembers the context each body was asked with, because the context carries
     * the operation key and the attempt — the two facts that decide whether a second ask is
     * the SAME operation or a new one.
     */
    private class RecordingSlotInvoker(
        private val outcomes: Map<PluginStepId, BodyOutcome> = emptyMap(),
        private val onInvoke: (PluginStepId) -> Unit = {},
    ) : BodySlotInvoker {
        val invoked = mutableListOf<PluginStepId>()
        val contexts = mutableListOf<BodyInvocationContext>()

        override suspend fun invoke(
            slot: PluginStepId,
            context: BodyInvocationContext,
        ): BodyOutcome {
            invoked += slot
            contexts += context
            onInvoke(slot)
            return outcomes[slot] ?: BodyOutcome.Completed(StepOutcome.Success)
        }
    }

    /**
     * The event sink, recorded.
     *
     * The loop announces three facts and the order matters: a compensation that started, the
     * compensation that finished, and the Saga's terminal state. A reader that sees a start
     * with no finish knows the answer was lost.
     */
    private class RecordingEventSink : EventSink {
        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
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
