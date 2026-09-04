package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class CanonicalNodeDispatcherTest {
    @Test
    fun `dispatches a canonical echo node through the single runtime seam`() = runBlocking {
        val eventStore = InMemoryEventStore()
        val runId = "canonical-dispatch-run"
        val command = CanonicalCoreStepCommand.Echo(text = "single seam")

        val outcome = CanonicalNodeDispatcher().dispatch(
            command,
            CanonicalRuntimeContext(
                opId = OpId(runId, 0, 0),
                runId = runId,
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = null,
                eventSink = eventStore,
            ),
        )

        assertEquals(StepOutcome.Success, outcome)
        assertEquals("single seam\n", (eventStore.eventsFor(runId).single() as EchoOutputCaptured).content)
    }
}
