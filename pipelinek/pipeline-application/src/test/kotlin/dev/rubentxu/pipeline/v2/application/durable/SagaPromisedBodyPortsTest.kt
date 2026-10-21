package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.saga.ArmRejection
import dev.rubentxu.pipeline.v2.domain.saga.CompensationArmOutcome
import dev.rubentxu.pipeline.v2.domain.saga.CompensationBodyDigest
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDeclaration
import dev.rubentxu.pipeline.v2.domain.saga.CompensationDirective
import dev.rubentxu.pipeline.v2.domain.saga.CompensationId
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKey
import dev.rubentxu.pipeline.v2.domain.saga.CompensationKind
import dev.rubentxu.pipeline.v2.domain.saga.CompensationPolicy
import dev.rubentxu.pipeline.v2.domain.saga.EffectEvidence
import dev.rubentxu.pipeline.v2.domain.saga.EffectReceiptRef
import dev.rubentxu.pipeline.v2.domain.saga.InterventionReason
import dev.rubentxu.pipeline.v2.domain.saga.ResourceRef
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionId
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaBodyVerdict
import dev.rubentxu.pipeline.v2.domain.saga.SagaId
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlan
import dev.rubentxu.pipeline.v2.domain.saga.SagaPlanFingerprint
import dev.rubentxu.pipeline.v2.domain.saga.SagaRecordOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.SqliteConnectionFactory
import dev.rubentxu.pipeline.v2.events.durable.SqliteSagaObligationStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * The body a promise is paid with, as the PORTS see it.
 *
 * A promise says which body will undo the effect, and the engine measures that body when the
 * promise is made and again when the compensation is about to run. These are the rules that
 * make the two measurements mean the same thing: the plugin NAMES the body, the engine MEASURES
 * it, and a compensation whose body is not the promised one does not run.
 */
class SagaPromisedBodyPortsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): String = tempDir.resolve("run.sqlite").toString()

    private fun store() = SqliteSagaObligationStore(
        connectionFactory = { SqliteConnectionFactory.open(dbPath()) },
        dbPath = dbPath(),
    )

    /**
     * The intent port, as a plugin would hold it.
     *
     * [bodies] is the body the step owns: it is what the engine measures the promise against.
     */
    private fun intent(bodies: SagaBodyResolver?) = SagaCompensationOperations(
        runId = "run-1",
        forwardOpId = "run-1|stage-0|step-reserve",
        leaseEpoch = 1L,
        store = store(),
        eventSink = NullEventSink,
        clock = FixedClock,
        ownerPluginId = "example.saga",
        bodies = bodies,
    )

    private fun planOperations() = SagaPlanOperationsAdapter(
        runId = "run-1",
        leaseEpoch = 1L,
        store = store(),
        clock = FixedClock,
        ownerPluginId = "example.saga",
    )

    private fun declaration(compensateSlot: PluginStepId?) = CompensationDeclaration(
        sagaId = SAGA,
        actionId = ACTION,
        compensationId = COMPENSATION,
        planFingerprint = PLAN,
        resourceRef = ResourceRef.of("stock/reservation-42"),
        kind = CompensationKind.RESTORE,
        compensateSlot = compensateSlot,
    )

    /**
     * The body of a Saga step: a child that performs the effect and a child that undoes it.
     *
     * The compensating child carries a payload, so a body whose payload changed is a body
     * whose SHAPE changed, and that is the difference the promise has to notice.
     */
    private fun body(compensatePayload: String = "{\"undo\":true}") = listOf(
        OpaqueStepNode(
            id = StepId("body/sh-0"),
            pluginStepId = PluginStepId("core.sh"),
            payload = VersionedStepPayload("dsl-v1", "{}"),
            slot = PluginStepId(FORWARD),
        ),
        OpaqueStepNode(
            id = StepId("body/sh-1"),
            pluginStepId = PluginStepId("core.sh"),
            payload = VersionedStepPayload("dsl-v1", compensatePayload),
            slot = PluginStepId(COMPENSATE),
        ),
    )

    private fun digestOf(body: List<dev.rubentxu.pipeline.v2.domain.StepNode>): CompensationBodyDigest =
        CompensationBodyDigest(BodyStructureDigest.of(body)!!)

    @Test
    fun `the promise is armed with the body it names`() {
        val outcome = intent(SagaBodyResolver(body())).arm(declaration(PluginStepId(COMPENSATE)))

        assertInstanceOf<CompensationArmOutcome.Armed>(outcome)

        val stored = store().find("run-1", KEY)?.bodyDigest
        assertNotNull(stored)
        // It is the COMPENSATING body, not the forward one and not the whole body: a promise
        // is about one body, and a change elsewhere must not invalidate it.
        assertNotEquals(digestOf(body().take(1)), stored)
        assertNotEquals(digestOf(body()), stored)
    }

    @Test
    fun `a promise that names a body nobody has is refused, and the refusal names the slot`() {
        val outcome = intent(SagaBodyResolver(body())).arm(declaration(PluginStepId("reserve.nope")))

        val rejected = assertInstanceOf<CompensationArmOutcome.Rejected>(outcome)
        val refusal = assertInstanceOf<ArmRejection.UnknownCompensationBody>(rejected.reason)
        assertEquals(PluginStepId("reserve.nope"), refusal.slot)
        // No promise was made, so no effect may run behind it.
        assertNull(store().find("run-1", KEY))
    }

    @Test
    fun `a promise that names a body in a step that has no body is refused`() {
        val outcome = intent(bodies = null).arm(declaration(PluginStepId(COMPENSATE)))

        val rejected = assertInstanceOf<CompensationArmOutcome.Rejected>(outcome)
        assertInstanceOf<ArmRejection.UnknownCompensationBody>(rejected.reason)
        assertNull(store().find("run-1", KEY))
    }

    @Test
    fun `the compensation runs when the body that runs is the body that was promised`() {
        armAndConfirm(compensateSlot = PluginStepId(COMPENSATE))
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            interpreter(slots, bodies = SagaBodyResolver(body())).compensate(plan())
        }

        assertEquals(listOf(PluginStepId(COMPENSATE)), slots.invoked)
        assertInstanceOf<CompensationDirective.Settled>(report.directive)
    }

    @Test
    fun `the compensation is refused when the body that would run is not the promised one`() {
        armAndConfirm(compensateSlot = PluginStepId(COMPENSATE))
        val slots = RecordingSlotInvoker()

        // The body changed: the plugin was upgraded and the compensating child carries
        // another payload. The promise named the body as it was.
        val report = runBlocking {
            interpreter(slots, bodies = SagaBodyResolver(body(compensatePayload = "{\"undo\":false}")))
                .compensate(plan())
        }

        val stop = assertInstanceOf<CompensationDirective.NeedsIntervention>(report.directive)
        assertEquals(InterventionReason.COMPENSATION_BODY_DIVERGED, stop.reason)
        // The reason says WHICH body was promised and WHICH one was going to run.
        val divergence = assertInstanceOf<SagaBodyVerdict.Diverged>(stop.divergence)
        assertEquals(digestOf(body().take(2).drop(1)), divergence.promised)
        assertEquals(digestOf(body(compensatePayload = "{\"undo\":false}").take(2).drop(1)), divergence.current)
        assertTrue(divergence.describe().contains(divergence.promised.hex), divergence.describe())
        assertTrue(divergence.describe().contains(divergence.current!!.hex), divergence.describe())
        // And the body did NOT run: nothing was undone with a body nobody promised.
        assertTrue(slots.invoked.isEmpty(), slots.invoked.toString())
    }

    @Test
    fun `a compensation whose answer was lost is not asked again with a body nobody promised`() {
        armAndConfirm(compensateSlot = PluginStepId(COMPENSATE))
        // The compensation was dispatched and its answer was lost: the row says "in flight",
        // so the only way out is to ask the body again.
        assertInstanceOf<SagaRecordOutcome.Recorded>(planOperations().beginCompensation(KEY))
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            interpreter(slots, bodies = SagaBodyResolver(body(compensatePayload = "{\"undo\":false}")))
                .compensate(reconcilablePlan())
        }

        val stop = assertInstanceOf<CompensationDirective.NeedsIntervention>(report.directive)
        assertEquals(InterventionReason.COMPENSATION_BODY_DIVERGED, stop.reason)
        // The answer was lost, but the promise did not change: the body is not asked again.
        assertTrue(slots.invoked.isEmpty(), slots.invoked.toString())
    }

    @Test
    fun `a promise that names no body is compensated, because it cannot contradict one`() {
        armAndConfirm(compensateSlot = null)
        val slots = RecordingSlotInvoker()

        val report = runBlocking {
            interpreter(slots, bodies = SagaBodyResolver(body())).compensate(plan())
        }

        assertEquals(listOf(PluginStepId(COMPENSATE)), slots.invoked)
        assertInstanceOf<CompensationDirective.Settled>(report.directive)
    }

    /**
     * Arms a promise and reports that its forward effect happened.
     */
    private fun armAndConfirm(compensateSlot: PluginStepId?) {
        val declaration = declaration(compensateSlot)
        assertInstanceOf<CompensationArmOutcome.Armed>(
            intent(SagaBodyResolver(body())).arm(declaration),
        )
        assertInstanceOf<SagaRecordOutcome.Recorded>(
            planOperations().recordForward(
                declaration.key,
                EffectEvidence.Confirmed(EffectReceiptRef.of("rcpt-reserve")),
            ),
        )
    }

    private fun plan() = SagaPlan(
        sagaId = SAGA,
        policy = CompensationPolicy.NotReconcilable,
        actions = listOf(
            SagaActionPlan(
                actionId = ACTION,
                compensationId = COMPENSATION,
                resourceRef = ResourceRef.of("stock/reservation-42"),
                kind = CompensationKind.RESTORE,
                forwardSlot = PluginStepId(FORWARD),
                compensateSlot = PluginStepId(COMPENSATE),
            ),
        ),
    )

    /**
     * The same plan, for a Saga whose lost answers may be settled by ASKING THE PROVIDER.
     */
    private fun reconcilablePlan() = plan().copy(policy = CompensationPolicy.Reconcilable)

    private fun interpreter(
        slots: BodySlotInvoker,
        bodies: SagaBodyResolver?,
    ) = SagaCompensationInterpreter(
        runId = "run-1",
        planOperations = planOperations(),
        completion = SagaCompletionAdapter(
            runId = "run-1",
            leaseEpoch = 1L,
            store = store(),
            clock = FixedClock,
        ),
        slotInvoker = slots,
        eventSink = NullEventSink,
        clock = FixedClock,
        bodies = bodies,
        // The body each promise is paid with, read from the durable row — as the engine does.
        promisedBodies = SagaPromisedBodyReader { key -> store().find("run-1", key)?.bodyDigest },
    )

    /**
     * The slot seam, recorded: it says which body the loop dispatched.
     */
    private class RecordingSlotInvoker : BodySlotInvoker {
        val invoked = mutableListOf<PluginStepId>()

        override suspend fun invoke(
            slot: PluginStepId,
            context: BodyInvocationContext,
        ): BodyOutcome {
            invoked += slot
            return BodyOutcome.Completed(StepOutcome.Success)
        }
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }

    private companion object {
        val SAGA = SagaId.of("checkout")
        val ACTION = SagaActionId.of("reserve-stock")
        val COMPENSATION = CompensationId.of("release-stock")
        val PLAN = SagaPlanFingerprint("a".repeat(64))
        const val FORWARD = "reserve.forward"
        const val COMPENSATE = "reserve.compensate"
        val KEY = CompensationKey(
            sagaId = SAGA,
            actionId = ACTION,
            compensationId = COMPENSATION,
        )
    }
}
