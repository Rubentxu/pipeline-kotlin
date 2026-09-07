package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class CanonicalWaitUntilNodeDispatcherTest {
    @Test
    fun `dispatchStub emits WaitUntilPolled and WaitUntilCompleted with completed outcome`() {
        val eventStore = InMemoryEventStore()
        val runId = "canonical-waitUntil-stub-run"
        val command = CanonicalCoreStepCommand.WaitUntil(
            initialRecurrencePeriod = 1000L,
            quiet = false,
        )

        val outcome = CanonicalWaitUntilNodeDispatcher().dispatchStub(
            command,
            CanonicalWaitUntilDispatchContext(
                runId = runId,
                stepIndex = 0,
                eventSink = eventStore,
                condition = { true },
            ),
        )

        assertEquals(StepOutcome.Success, outcome)

        val polledEvents = eventStore.eventsFor(runId).filterIsInstance<WaitUntilPolled>().toList()
        assertEquals(1, polledEvents.size, "Should emit one WaitUntilPolled event")
        assertEquals(1, polledEvents.first().attempt)
        assertTrue(polledEvents.first().conditionResult, "Stub condition should be true")

        val completedEvents = eventStore.eventsFor(runId).filterIsInstance<WaitUntilCompleted>().toList()
        assertEquals(1, completedEvents.size, "Should emit one WaitUntilCompleted event")
        assertEquals("completed", completedEvents.first().outcome)
        assertEquals(1, completedEvents.first().totalAttempts)
    }

    @Test
    fun `dispatch with immediate condition success emits events and returns success`() {
        val eventStore = InMemoryEventStore()
        val runId = "canonical-waitUntil-immediate-run"
        val command = CanonicalCoreStepCommand.WaitUntil(
            initialRecurrencePeriod = 100L,
            quiet = false,
        )

        // Condition returns true immediately
        val outcome = CanonicalWaitUntilNodeDispatcher().dispatch(
            command,
            CanonicalWaitUntilDispatchContext(
                runId = runId,
                stepIndex = 0,
                eventSink = eventStore,
                condition = { true },
            ),
            deadlineMs = Long.MAX_VALUE,
        )

        assertEquals(StepOutcome.Success, outcome)

        val polledEvents = eventStore.eventsFor(runId).filterIsInstance<WaitUntilPolled>().toList()
        assertTrue(polledEvents.isNotEmpty(), "Should emit at least one WaitUntilPolled event")

        val completedEvents = eventStore.eventsFor(runId).filterIsInstance<WaitUntilCompleted>().toList()
        assertEquals(1, completedEvents.size, "Should emit one WaitUntilCompleted event")
        assertEquals("completed", completedEvents.first().outcome)
    }
}
