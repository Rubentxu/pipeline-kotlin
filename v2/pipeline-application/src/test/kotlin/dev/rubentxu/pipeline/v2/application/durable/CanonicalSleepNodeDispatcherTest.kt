package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class CanonicalSleepNodeDispatcherTest {
    @Test
    fun `dispatches a canonical sleep node without creating an output event`() {
        val eventStore = InMemoryEventStore()
        val runId = "canonical-sleep-run"
        val command = CanonicalCoreStepCommand.Sleep(seconds = 0)

        val outcome = CanonicalSleepNodeDispatcher().dispatch(
            command,
            CanonicalSleepDispatchContext(
                runId = runId,
                stepIndex = 0,
                eventSink = eventStore,
            ),
        )

        assertEquals(StepOutcome.Success, outcome)
        assertTrue(eventStore.eventsFor(runId).none())
    }
}
