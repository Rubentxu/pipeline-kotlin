package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S2-A8 / G2 — Differential Contract Freeze for `core.waitUntil`.
 *
 * Drives the registry candidate ([CoreWaitUntilStep]) and verifies:
 * - Input codec produces the canonical dsl-v1 envelope
 * - Handler emits WaitUntilPolled and WaitUntilCompleted events
 * - Output codec produces the expected envelope
 * - Semantic parity with legacy stub pattern
 *
 * waitUntil is a Block Step with a condition body. The registry candidate follows
 * the stub pattern from the legacy dispatcher (condition assumed true).
 */
@Timeout(30)
class CoreWaitUntilDifferentialContractTest {

    private fun testContext(eventStore: InMemoryEventStore): StepHandlerContext =
        StepHandlerContext(
            runId = RunId("waituntil-diff"),
            stepIndex = 0,
            capabilities = object : dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess {
                override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> =
                    setOf(EVENT_SINK_CAPABILITY)

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> get(
                    key: dev.rubentxu.pipeline.v2.domain.step.StepCapability
                ): T = when (key) {
                    EVENT_SINK_CAPABILITY -> eventStore as T
                    else -> throw IllegalArgumentException("unavailable: $key")
                }
            },
        )

    // ===== 1. input codec produces canonical dsl-v1 envelope =====

    @Test
    fun `input codec — WaitUntilInput produces canonical dsl-v1 envelope with correct defaults`() {
        val input = WaitUntilInput(initialRecurrencePeriod = 500L, quiet = true)
        val encoded = CoreWaitUntilStep.definition.contract.inputCodec.encode(input)

        // Must match the legacy decoder expectation
        assertTrue(encoded.value.contains("\"kind\":\"waitUntil\""))
        assertTrue(encoded.value.contains("\"initialRecurrencePeriod\":500"))
        assertTrue(encoded.value.contains("\"quiet\":true"))
    }

    @Test
    fun `input codec — default values produce expected envelope`() {
        val input = WaitUntilInput() // defaults: period=1000, quiet=false
        val encoded = CoreWaitUntilStep.definition.contract.inputCodec.encode(input)

        assertTrue(encoded.value.contains("\"initialRecurrencePeriod\":1000"))
        assertTrue(encoded.value.contains("\"quiet\":false"))
    }

    // ===== 2. handler emits events =====

    @Test
    fun `handler — emits WaitUntilPolled and WaitUntilCompleted events with stub pattern`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val ctx = testContext(eventStore)

        val input = WaitUntilInput(initialRecurrencePeriod = 500L, quiet = false)
        val output = CoreWaitUntilStep.definition.handler.execute(input, ctx)

        // Verify events emitted
        val polledEvents = eventStore.eventsFor("waituntil-diff").filterIsInstance<WaitUntilPolled>().toList()
        val completedEvents = eventStore.eventsFor("waituntil-diff").filterIsInstance<WaitUntilCompleted>().toList()

        assertEquals(1, polledEvents.size, "should emit exactly one WaitUntilPolled event")
        assertEquals(1, completedEvents.size, "should emit exactly one WaitUntilCompleted event")

        // Verify stub pattern: condition assumed true
        assertTrue(polledEvents.first().conditionResult, "stub condition should be true")
        assertEquals("completed", completedEvents.first().outcome, "stub outcome should be 'completed'")
    }

    // ===== 3. output codec =====

    @Test
    fun `output codec — WaitUntilOutput round-trips correctly`() {
        val original = WaitUntilOutput(
            resultOutcome = "completed",
            totalAttempts = 3,
            totalDurationMs = 1500L,
        )
        val encoded = CoreWaitUntilStep.definition.contract.outputCodec.encode(original)
        val decoded = CoreWaitUntilStep.definition.contract.outputCodec.decode(encoded)

        assertEquals(original.resultOutcome, decoded.resultOutcome)
        assertEquals(original.totalAttempts, decoded.totalAttempts)
        assertEquals(original.totalDurationMs, decoded.totalDurationMs)
    }

    // ===== 4. handler output matches expected stub semantics =====

    @Test
    fun `handler — stub output has completed outcome with zero attempts-durations`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val ctx = testContext(eventStore)

        val output = CoreWaitUntilStep.definition.handler.execute(WaitUntilInput(), ctx)

        assertEquals("completed", output.resultOutcome)
        assertEquals(1, output.totalAttempts)
        assertEquals(0L, output.totalDurationMs)
    }

    // ===== 5. codec compatibility with legacy decoder =====

    @Test
    fun `codec round-trip — encoded by registry, decodeable as legacy would expect`() {
        val input = WaitUntilInput(initialRecurrencePeriod = 1000L, quiet = false)
        val encoded = CoreWaitUntilStep.definition.contract.inputCodec.encode(input)
        val decoded = CoreWaitUntilStep.definition.contract.inputCodec.decode(encoded)

        assertEquals(input.initialRecurrencePeriod, decoded.initialRecurrencePeriod)
        assertEquals(input.quiet, decoded.quiet)
    }

    // ===== 6. event fields are properly populated =====

    @Test
    fun `handler — WaitUntilPolled has correct attempt and duration fields`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val ctx = testContext(eventStore)

        CoreWaitUntilStep.definition.handler.execute(WaitUntilInput(), ctx)

        val polled = eventStore.eventsFor("waituntil-diff").filterIsInstance<WaitUntilPolled>().first()
        assertEquals(1, polled.attempt, "stub should have attempt=1")
        assertEquals(0L, polled.durationMs, "stub should have durationMs=0")
        assertTrue(polled.eventId.isNotBlank(), "eventId should be non-blank")
        assertTrue(polled.runId.isNotBlank(), "runId should be non-blank")
    }

    @Test
    fun `handler — WaitUntilCompleted has correct totalAttempts and totalDurationMs fields`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val ctx = testContext(eventStore)

        CoreWaitUntilStep.definition.handler.execute(WaitUntilInput(), ctx)

        val completed = eventStore.eventsFor("waituntil-diff").filterIsInstance<WaitUntilCompleted>().first()
        assertEquals(1, completed.totalAttempts, "stub should have totalAttempts=1")
        assertEquals(0L, completed.totalDurationMs, "stub should have totalDurationMs=0")
        assertTrue(completed.eventId.isNotBlank(), "eventId should be non-blank")
    }

    // ===== 7. BLOCK STEP NOTE =====

    /**
     * waitUntil is a Block Step with a condition body. The registry candidate
     * follows the stub pattern:
     * - Emits WaitUntilPolled with conditionResult=true
     * - Emits WaitUntilCompleted with outcome="completed"
     *
     * The actual polling loop with condition evaluation requires BodyInvoker (ADR-0073).
     * This differential test verifies the stub semantics only.
     */
}
