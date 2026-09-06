package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class DurableShellCommandTest {
    @Test
    fun `linear shell step exposes its closed invocation result`() = runBlocking {
        val outcome = ShExecution.runShStep(
            step = StepSpec.Shell(command = "exit 0"),
            opId = OpId("canonical-run", 0, 0),
            runId = "canonical-run",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
        )

        assertEquals(ShellInvocationResult.UnitValue, outcome)
    }

    @Test
    fun `branch shell step exposes script failure without a String projection`() = runBlocking {
        val outcome = ShExecution.executeBranchStep(
            stageIndex = 0,
            stepIndex = 0,
            branchOpId = OpId.forBranch("canonical-branch", 0, 0, 0),
            runId = "canonical-branch",
            command = ShellCommand("exit 17"),
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
        )

        assertEquals(ShellInvocationResult.Failed(
            failure = dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
                "shell exited with code 17",
            ),
            exitCode = 17,
        ), outcome)
    }
}
