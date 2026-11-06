package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.runtime.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.MILESTONE_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.MilestoneOperations
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.runtime.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.step.AllowAll
import dev.rubentxu.pipeline.v2.domain.step.CapabilityRefusal
import dev.rubentxu.pipeline.v2.domain.step.DenyAll
import dev.rubentxu.pipeline.v2.domain.step.NETWORK_EGRESS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.CapabilityRefusedException
import dev.rubentxu.pipeline.v2.domain.step.NetworkEgressGate
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.runtime.telemetry.NoopStepSpan
import dev.rubentxu.pipeline.v2.runtime.telemetry.ObservationName
import dev.rubentxu.pipeline.v2.runtime.telemetry.ObservationStatus
import dev.rubentxu.pipeline.v2.runtime.telemetry.STEP_OBSERVABILITY_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.telemetry.StepObservability
import dev.rubentxu.pipeline.v2.runtime.telemetry.StepSpan
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * The capability table is read with a DECLARED type, and the two ways that read can fail are
 * named refusals rather than a cast failure.
 *
 * ## The guarantee
 *
 * ```text
 * present      → the value, with the type the consumer declared
 * absent       → CapabilityRefusal.Absent
 * wrong shape  → CapabilityRefusal.Incompatible
 * ```
 *
 * Before the cut the access said nothing about the type it handed over: the type parameter was
 * erased, so the only check that ever ran was "is it null". A value of the wrong shape was
 * therefore delivered, and the mismatch surfaced LATER, inside the consumer, as a cast failure —
 * the wrong reason, in the wrong place. A consumer could not tell "this run has no such
 * capability" from "this run has a different capability than the one I asked for", and neither
 * answer reached it as a fact.
 *
 * ## The capacity this serves
 *
 * A Step that declares a capability is entitled to know, at the point it asks, whether it is
 * being given what it declared. The refusal is the product's own vocabulary — a closed shape
 * with a reason — so a caller can switch on the reason instead of matching on a message.
 *
 * ## Why this instrument
 *
 * The instrument is the PRODUCTION bridge over the production holder, composed the way the
 * canonical coordinator composes it. Everything it touches is in memory or points at paths that
 * DO NOT EXIST: the workspace and the control root are placeholders, so a row that tried to
 * write there would say so instead of passing unnoticed, and the event sink discards.
 *
 * ## The rows, and the mutation that kills each
 *
 * ```text
 * a capability that is present is delivered with its declared type
 * a capability that is absent is refused with the named absence
 * a capability of another shape is refused with the named incompatibility
 * a seam the context carries is delivered as the very instance it carries
 * a seam the context does not carry is refused, never invented
 * ```
 *
 * The focal mutation removes ONE capability from the provision. It turns the first row red and
 * leaves the other two green, because the second row reads a capability the provision never had
 * and the third row reads a DIFFERENT one that is still there. A mutation that reddened every row
 * would be proving the fixture, not the guard.
 *
 * The last two rows are the observability seam, and they are one decision seen from both sides:
 * the bridge hands over what the context carries and nothing else. The mutation that reddens the
 * fourth row DROPS the binding: the fourth row goes red and the fifth stays green, because the
 * fifth row already reads a context that carries nothing. The mutation that reddens the fifth row
 * INVENTS a seam when the context carries none: the fifth row goes red, and the fourth row goes
 * red with it — that row then receives a seam the context never carried — and the KDoc says so
 * rather than pretending the two failures are independent.
 *
 * ## What this instrument cannot see
 *
 * It does not run a pipeline, does not touch a database and does not observe a handler. It
 * observes the ACCESS: what the table hands over, and how it says no.
 */
class RuntimeCapabilityAccessTest {

    @Test
    @DisplayName("a capability that is present is delivered with its declared type")
    fun aCapabilityThatIsPresentIsDeliveredWithItsDeclaredType() {
        val delivered = access().get(EVENT_SINK_CAPABILITY, EventSink::class)

        assertSame(
            context().eventSink,
            delivered,
            "the delivered value must be the very instance the runtime context carries",
        )
    }

    @Test
    @DisplayName("a capability that is absent is refused with the named absence")
    fun aCapabilityThatIsAbsentIsRefusedWithTheNamedAbsence() {
        val refusal = refusalFor(MILESTONE_OPERATIONS_CAPABILITY, MilestoneOperations::class)

        assertInstanceOf(
            CapabilityRefusal.Absent::class.java,
            refusal,
            "a capability nobody provisioned is refused as an absence, not as a mismatch",
        )
        assertEquals(
            "capability unavailable to this invocation: $MILESTONE_OPERATIONS_CAPABILITY",
            refusal.describe(),
            "the refusal keeps the wording the product has always emitted for this case",
        )
    }

    @Test
    @DisplayName("a capability of another shape is refused with the named incompatibility")
    fun aCapabilityOfAnotherShapeIsRefusedWithTheNamedIncompatibility() {
        val refusal = refusalFor(WORKSPACE_IDENTITY_CAPABILITY, String::class)

        val incompatible = assertInstanceOf(
            CapabilityRefusal.Incompatible::class.java,
            refusal,
            "a provisioned value of another shape is refused as an incompatibility",
        )
        assertEquals(
            String::class,
            incompatible.expected,
            "the refusal names the type the consumer declared",
        )
        assertEquals(
            WorkspaceIdentity::class,
            incompatible.actual,
            "the refusal names the type that was actually provisioned",
        )
    }

    @Test
    @DisplayName("the observability seam the context carries is delivered as that very instance")
    fun theObservabilitySeamTheContextCarriesIsDeliveredAsThatVeryInstance() {
        val seam = CarriedSeam()

        val delivered = access(context(seam)).get(STEP_OBSERVABILITY_CAPABILITY, StepObservability::class)

        assertSame(
            seam,
            delivered,
            "the seam handed over must be the very instance the runtime context carries",
        )
    }

    @Test
    @DisplayName("a context that carries no observability seam is refused one, never given an invented one")
    fun aContextThatCarriesNoObservabilitySeamIsRefusedOneNeverGivenAnInventedOne() {
        val refusal = refusalFor(
            key = STEP_OBSERVABILITY_CAPABILITY,
            type = StepObservability::class,
            holding = context(),
        )

        assertInstanceOf(
            CapabilityRefusal.Absent::class.java,
            refusal,
            "a context that carries no seam exposes no seam: the bridge hands over what it has",
        )
    }

    @Test
    @DisplayName("a run that grants no egress to its plugins hands out no egress capability")
    fun aRunThatGrantsNoEgressToItsPluginsHandsOutNoEgressCapability() {
        val provided = access(context())

        assertFalse(
            provided.available().contains(NETWORK_EGRESS_CAPABILITY),
            "a run that denies egress to its plugins still exposes the egress capability, " +
                "so the admission refuses nothing and the plugin opens the socket anyway",
        )
    }

    @Test
    @DisplayName("a run that was asked for egress hands the capability to the plugins")
    fun aRunThatWasAskedForEgressHandsTheCapabilityToThePlugins() {
        val provided = access(context(pluginEgress = AllowAll))

        assertTrue(
            provided.available().contains(NETWORK_EGRESS_CAPABILITY),
            "a run that was asked for egress does not expose the capability, so a plugin " +
                "that declares it is refused at admission for no reason at all",
        )
    }

    // --- the fixtures ------------------------------------------------------
    /**
     * The refusal the access raises, read back off the exception that carries it.
     *
     * The exception is the transport and the refusal is the fact: a caller switches on the
     * refusal, never on the message.
     */
    private fun <T : Any> refusalFor(
        key: StepCapability,
        type: KClass<T>,
        holding: RuntimeContext = context(),
    ): CapabilityRefusal {
        val thrown = assertThrows<CapabilityRefusedException> {
            access(holding).get(key, type)
        }
        return thrown.refusal
    }

    /**
     * The production access, composed the way the canonical coordinator composes it: the
     * production holder with no collaborators bound, over a runtime context that carries no
     * milestone store, no artifact index and no plugin contributor.
     */
    private fun access(holding: RuntimeContext = context()): RuntimeCapabilityAccess =
        CapabilityBindings().access(holding)

    /**
     * The runtime context, pointing at paths that do not exist and discarding every event.
     *
     * The seam is a parameter because whether the context carries one is a fact about how the
     * context was composed, and the rows above need both answers.
     *
     * The egress gate is a parameter for the same reason: the rows that read it need both
     * answers, and a fixture that hard-coded the denying gate could not tell a gate that
     * withholds the capability from a gate that was never consulted.
     */
    private fun context(
        stepObservability: StepObservability? = null,
        pluginEgress: NetworkEgressGate = DenyAll,
    ): RuntimeContext =
        RuntimeContext(
            opId = OpId(RUN_ID, 0, 0),
            runId = RUN_ID,
            stageName = "stage-0",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(
                workspaceRoot = Path.of("/nonexistent-workspace"),
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
                pluginEgress = pluginEgress,
            ),
            controlDirRoot = Path.of("/nonexistent-control-root"),
            eventSink = NullEventSink,
            clock = FixedClock,
            idGenerator = SequentialIdGenerator(),
            stepObservability = stepObservability,
        )

    /**
     * A seam of the right shape that is NOT the product's own noop.
     *
     * The row this serves is about IDENTITY: the bridge must hand over the instance the
     * context carries. A fixture that reused the product's noop would be satisfied by ANY
     * seam of that type, and would say nothing about WHICH one was handed over.
     */
    private class CarriedSeam : StepObservability {
        override val enabled: Boolean = true

        override fun openSpan(name: ObservationName): StepSpan = NoopStepSpan

        override fun count(name: ObservationName, status: ObservationStatus) = Unit
    }

    private companion object {
        const val RUN_ID = "run-1"
    }
    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }
}
