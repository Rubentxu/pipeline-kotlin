package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class CanonicalShellNodeDispatcherTest {
    @Test
    fun `dispatches a canonical shell node through the durable shell command path`() = runBlocking {
        val dispatcher = CanonicalShellNodeDispatcher()
        val command = CanonicalCoreStepCommand.Shell(
            command = "exit 0",
            isScriptBlock = false,
            returnStdout = false,
        )
        val context = CanonicalShellDispatchContext(
            opId = OpId("canonical-run", 0, 0),
            runId = "canonical-run",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
        )

        assertEquals(StepOutcome.Success, dispatcher.dispatch(command, context))
    }
}
