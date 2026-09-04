package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class DurableShellCommandTest {
    @Test
    fun `executes a typed shell command without constructing a DSL step`() = runBlocking {
        val outcome = ShExecution.runShellCommand(
            command = DurableShellCommand("exit 0"),
            opId = OpId("canonical-run", 0, 0),
            runId = "canonical-run",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
        )

        assertEquals("success", outcome)
    }
}
