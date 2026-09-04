package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
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
        val node = OpaqueStepNode(
            id = StepId("build/sleep-immediate"),
            pluginStepId = PluginStepId("core.sleep"),
            payload = VersionedStepPayload("dsl-v1", """{"kind":"sleep","seconds":0}"""),
        )

        val outcome = CanonicalSleepNodeDispatcher().dispatch(
            node,
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
