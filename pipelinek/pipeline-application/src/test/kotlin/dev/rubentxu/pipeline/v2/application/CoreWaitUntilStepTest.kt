package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilCompletion
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.KClass

/**
 * The fallback route of `core.waitUntil` REFUSES instead of reporting a verdict it did not reach.
 *
 * The guarantee under test is the one a consumer relies on: a step that cannot evaluate its
 * predicate ends the run with a NAMED refusal, in the product's own vocabulary, and never with a
 * success it did not observe.
 */
class CoreWaitUntilStepTest {

    /**
     * The guarantee, in one row.
     *
     * Every other row in this file describes a shape of the wire or of the contract; only this one
     * exercises the handler. That is deliberate: it is what makes the focal mutation observable as
     * EXACTLY one red row, and it is why the "no event was published" claim lives HERE instead of
     * in a row of its own.
     */
    @Test
    fun `la ruta de respaldo rechaza con el motivo de capacidad no soportada y no publica ningun evento`() {
        val sink = RecordingEventSink()
        val capabilities = RecordingCapabilityAccess(setOf(EVENT_SINK_CAPABILITY), sink)

        val output = runBlocking {
            CoreWaitUntilStep.definition.handler.execute(
                WaitUntilInput(),
                StepHandlerContext(
                    runId = RunId("run-1"),
                    stepIndex = 0,
                    capabilities = capabilities,
                ),
            )
        }

        assertTrue(output is WaitUntilOutput.Refused) {
            "the fallback route must refuse, but it returned ${output::class.simpleName}"
        }
        val refused = output as WaitUntilOutput.Refused

        assertEquals(
            "missing required capabilities for step 'core.waitUntil': bodyContinuation",
            refused.reason,
        )
        assertEquals(listOf("bodyContinuation"), refused.missingCapabilities)
        assertEquals(
            StepOutcome.Failure(PipelineFailure(FailureKind.SCHEMA, refused.reason)),
            refused.outcome,
        )

        // The refusal is a verdict about a step that could NOT run: it publishes nothing, and it
        // does not even reach for the event sink, because the engine publishes the durable
        // observations on the canonical path — not here.
        assertEquals(0, sink.events.size) { "the refusal published ${sink.events.size} event(s)" }
        assertTrue(capabilities.requested.isEmpty()) {
            "the refusal asked for ${capabilities.requested}; it must not reach for any capability"
        }
    }

    @Test
    fun `la forma historica del wire sigue decodificando al terminal que termino la espera`() {
        val decoded = CoreWaitUntilStep.definition.contract.outputCodec.decode(
            EncodedStepValue(
                """{"kind":"waitUntil","outcome":"completed","totalAttempts":3,"totalDurationMs":42}""",
            ),
        )

        assertTrue(decoded is WaitUntilOutput.Completed) {
            "the historical shape must decode to a completed wait, but it decoded to " +
                "${decoded::class.simpleName}"
        }
        val completed = decoded as WaitUntilOutput.Completed

        assertEquals(WaitUntilCompletion.Satisfied, completed.completion)
        assertEquals("completed", completed.resultOutcome)
        assertEquals(3, completed.totalAttempts)
        assertEquals(42L, completed.totalDurationMs)
        assertEquals(StepOutcome.Success, completed.outcome)
    }

    @Test
    fun `la forma de negativa hace round trip sin perder el motivo ni las capacidades`() {
        val codec = CoreWaitUntilStep.definition.contract.outputCodec
        val refused = WaitUntilOutput.Refused(
            reason = "missing required capabilities for step 'core.waitUntil': bodyContinuation",
            missingCapabilities = listOf("bodyContinuation"),
        )

        val decoded = codec.decode(codec.encode(refused))

        assertTrue(decoded is WaitUntilOutput.Refused) {
            "the refusal shape must round trip, but it decoded to ${decoded::class.simpleName}"
        }
        val decodedRefusal = decoded as WaitUntilOutput.Refused
        assertEquals(refused.reason, decodedRefusal.reason)
        assertEquals(refused.missingCapabilities, decodedRefusal.missingCapabilities)
    }

    @Test
    fun `una forma de salida desconocida se rechaza en vez de interpretarse`() {
        assertThrows<IllegalArgumentException> {
            CoreWaitUntilStep.definition.contract.outputCodec.decode(
                EncodedStepValue("""{"kind":"waitUntilBlock","outcome":"completed"}"""),
            )
        }
    }

    @Test
    fun `un terminal cuyos parametros no viajan por el cable se rechaza en vez de inventarse`() {
        assertThrows<IllegalArgumentException> {
            CoreWaitUntilStep.definition.contract.outputCodec.decode(
                EncodedStepValue(
                    """{"kind":"waitUntil","outcome":"deadline-exceeded","totalAttempts":3,""" +
                        """"totalDurationMs":42}""",
                ),
            )
        }
    }

    @Test
    fun `el contrato no declara la capacidad de continuacion porque su owner es el motor canonico`() {
        val required = CoreWaitUntilStep.definition.contract.requiredCapabilities

        assertFalse(BODY_CONTINUATION_CAPABILITY in required) {
            "a CANONICAL_ENGINE owner has no continuation to bind; declaring the capability " +
                "would be incoherent and the plugin manifest must declare exactly the contract's"
        }
        assertEquals(setOf(EVENT_SINK_CAPABILITY), required)
    }

    /** A capability access that records what the handler asked for. */
    private class RecordingCapabilityAccess(
        private val availableCapabilities: Set<StepCapability>,
        private val sink: EventSink,
    ) : StepCapabilityAccess {

        val requested = mutableListOf<StepCapability>()

        override fun available(): Set<StepCapability> = availableCapabilities

        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T {
            requested += key
            require(key in availableCapabilities) {
                "capability unavailable to this invocation: $key"
            }
            require(type.isInstance(sink)) {
                "capability $key is not of the declared type: expected ${type.simpleName}, " +
                    "found ${sink::class.simpleName}"
            }
            return type.java.cast(sink)
        }
    }

    /** An event sink that keeps whatever it is given. */
    private class RecordingEventSink : EventSink {

        val events = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }
}
