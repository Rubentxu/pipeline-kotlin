package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY
import dev.rubentxu.pipeline.v2.application.MILESTONE_OPERATIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.application.MilestoneOperations
import dev.rubentxu.pipeline.v2.application.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.application.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.domain.step.CapabilityRefusal
import dev.rubentxu.pipeline.v2.domain.step.CapabilityRefusedException
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
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
 * ```
 *
 * The focal mutation removes ONE capability from the provision. It turns the first row red and
 * leaves the other two green, because the second row reads a capability the provision never had
 * and the third row reads a DIFFERENT one that is still there. A mutation that reddened every row
 * would be proving the fixture, not the guard.
 *
 * ## What this instrument cannot see
 *
 * It does not run a pipeline, does not touch a database and does not observe a handler. It
 * observes the ACCESS: what the table hands over, and how it says no.
 */
class CanonicalCapabilityAccessTest {

    @Test
    @DisplayName("a capability that is present is delivered with its declared type")
    fun aCapabilityThatIsPresentIsDeliveredWithItsDeclaredType() {
        val delivered = access().get(EVENT_SINK_CAPABILITY, EventSink::class)

        assertSame(
            context.eventSink,
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

    // --- the fixtures ------------------------------------------------------

    /**
     * The refusal the access raises, read back off the exception that carries it.
     *
     * The exception is the transport and the refusal is the fact: a caller switches on the
     * refusal, never on the message.
     */
    private fun <T : Any> refusalFor(key: StepCapability, type: KClass<T>): CapabilityRefusal {
        val thrown = assertThrows<CapabilityRefusedException> {
            access().get(key, type)
        }
        return thrown.refusal
    }

    /**
     * The production access, composed the way the canonical coordinator composes it: the
     * production holder with no collaborators bound, over a runtime context that carries no
     * milestone store, no artifact index and no plugin contributor.
     */
    private fun access(): CanonicalRuntimeCapabilityAccess =
        CanonicalCapabilityBindings().access(context)

    /** The runtime context, pointing at paths that do not exist and discarding every event. */
    private val context: CanonicalRuntimeContext = CanonicalRuntimeContext(
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
        ),
        controlDirRoot = Path.of("/nonexistent-control-root"),
        eventSink = NullEventSink,
    )

    private companion object {
        const val RUN_ID = "run-1"
    }
}
