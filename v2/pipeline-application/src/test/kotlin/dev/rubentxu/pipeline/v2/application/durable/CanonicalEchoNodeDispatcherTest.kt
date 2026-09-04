package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class CanonicalEchoNodeDispatcherTest {
    @Test
    fun `dispatches a canonical echo node and records its output`() {
        val eventStore = InMemoryEventStore()
        val runId = "canonical-echo-run"
        val node = OpaqueStepNode(
            id = StepId("build/echo-greeting"),
            pluginStepId = PluginStepId("core.echo"),
            payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"hello canonical runtime"}"""),
        )

        val outcome = CanonicalEchoNodeDispatcher().dispatch(
            node,
            CanonicalEchoDispatchContext(
                runId = runId,
                stepIndex = 0,
                eventSink = eventStore,
            ),
        )

        assertEquals(StepOutcome.Success, outcome)
        assertEquals(
            "hello canonical runtime\n",
            (eventStore.eventsFor(runId).single() as EchoOutputCaptured).content,
        )
    }
}
