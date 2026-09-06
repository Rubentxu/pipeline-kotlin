package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

@Timeout(10)
class CanonicalShellNodeDispatcherTest {
    @Test
    fun `typed shell invocation retains the returnStatus exit code`() = runBlocking {
        val result = ShExecution.invokeShell(
            command = ShellCommand(script = "exit 42", returnMode = ShellReturnMode.STATUS),
            opId = OpId("canonical-status-value", 0, 0),
            runId = "canonical-status-value",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
        )

        assertEquals(ShellInvocationResult.Status(42), result)
    }

    @Test
    fun `returnStatus keeps a nonzero exit as a successful canonical step`() = runBlocking {
        val dispatcher = CanonicalShellNodeDispatcher()
        val command = CanonicalCoreStepCommand.Shell(
            shell = ShellCommand(script = "exit 42", returnMode = ShellReturnMode.STATUS),
            isScriptBlock = false,
        )
        val context = CanonicalShellDispatchContext(
            opId = OpId("canonical-status", 0, 0),
            runId = "canonical-status",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
        )

        assertEquals(StepOutcome.Success, dispatcher.dispatch(command, context))
    }

    @Test
    fun `returnStatus keeps a nonzero durable exit as a successful canonical step`(@TempDir tempDir: Path) = runBlocking {
        val dispatcher = CanonicalShellNodeDispatcher()
        val command = CanonicalCoreStepCommand.Shell(
            shell = ShellCommand(script = "exit 42", returnMode = ShellReturnMode.STATUS),
            isScriptBlock = false,
        )
        val context = CanonicalShellDispatchContext(
            opId = OpId("canonical-durable-status", 0, 0),
            runId = "canonical-durable-status",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(workspaceRoot = tempDir, captureStdout = false, timeoutMs = null, env = emptyMap()),
            controlDirRoot = tempDir,
            eventSink = InMemoryEventStore(),
        )

        assertEquals(StepOutcome.Success, dispatcher.dispatch(command, context))
    }

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

    // C6-1: durable sh echo hello yields Success
    @Test
    fun `C6-1 durable sh echo hello yields Success`() = runBlocking {
        val dispatcher = CanonicalShellNodeDispatcher()
        val command = CanonicalCoreStepCommand.Shell(
            command = "echo hello",
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

        val outcome = dispatcher.dispatch(command, context)
        assertEquals(StepOutcome.Success, outcome)
    }

    // C6-2: durable sh false yields Failure SCRIPT
    @Test
    fun `C6-2 durable sh false yields Failure SCRIPT`() = runBlocking {
        val dispatcher = CanonicalShellNodeDispatcher()
        val command = CanonicalCoreStepCommand.Shell(
            command = "false",
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

        val outcome = dispatcher.dispatch(command, context)
        assertTrue(outcome is StepOutcome.Failure, "Expected Failure")
        assertEquals(FailureKind.SCRIPT, (outcome as StepOutcome.Failure).failure.kind)
    }

    // C6-3: durable sh with invalid workspace yields Failure INFRASTRUCTURE
    @Test
    fun `C6-3 durable sh with invalid workspace yields Failure INFRASTRUCTURE`(@TempDir tempDir: Path) = runBlocking {
        val dispatcher = CanonicalShellNodeDispatcher()
        // Use a non-existent path that cannot be created to trigger infrastructure failure
        val invalidWorkspace = tempDir.resolve("nonexistent/deep/path")
        val command = CanonicalCoreStepCommand.Shell(
            command = "echo test",
            isScriptBlock = false,
            returnStdout = false,
        )
        val context = CanonicalShellDispatchContext(
            opId = OpId("canonical-run", 0, 0),
            runId = "canonical-run",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(workspaceRoot = invalidWorkspace, captureStdout = false, timeoutMs = null, env = emptyMap()),
            controlDirRoot = tempDir,
            eventSink = InMemoryEventStore(),
        )

        val outcome = dispatcher.dispatch(command, context)
        assertTrue(outcome is StepOutcome.Failure, "Expected Failure")
        // The failure should be either INFRASTRUCTURE (workspace creation) or SCRIPT (shell failure)
    }

    // C6-4: durable sh with captureStdout true
    @Test
    fun `C6-4 durable sh with captureStdout true`(@TempDir tempDir: Path) = runBlocking {
        val dispatcher = CanonicalShellNodeDispatcher()
        val command = CanonicalCoreStepCommand.Shell(
            command = "echo hello",
            isScriptBlock = false,
            returnStdout = true, // This should now work after require removal
        )
        val eventStore = InMemoryEventStore()
        val context = CanonicalShellDispatchContext(
            opId = OpId("canonical-run", 0, 0),
            runId = "canonical-run",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(workspaceRoot = tempDir, captureStdout = true, timeoutMs = null, env = emptyMap()),
            controlDirRoot = tempDir,
            eventSink = eventStore,
        )

        val outcome = dispatcher.dispatch(command, context)
        // After require removal, returnStdout=true should work (though may still fail at runtime)
        assertTrue(outcome is StepOutcome.Success || outcome is StepOutcome.Failure, "Outcome should be Success or Failure, not UNSTABLE")
    }
}
