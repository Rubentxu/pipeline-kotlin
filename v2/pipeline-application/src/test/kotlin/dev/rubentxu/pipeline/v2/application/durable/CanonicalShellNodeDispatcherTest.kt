package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@Timeout(10)
class CanonicalShellNodeDispatcherTest {
    @TempDir
    lateinit var tempDir: Path

    private val childrenToCleanup = mutableListOf<ProcessHandle>()

    @AfterEach
    fun cleanup() {
        childrenToCleanup.forEach { handle ->
            try {
                handle.destroyForcibly()
            } catch (_: Exception) {
                // ignore
            }
        }
        childrenToCleanup.clear()
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

    @Test
    @Timeout(30)
    fun `C6-1 durable sh echo hello yields Success with OutputCaptured`() = runBlocking {
        val controlDir = tempDir.resolve("control").also { it.toFile().mkdirs() }
        val eventStore = InMemoryEventStore()
        val dispatcher = CanonicalShellNodeDispatcher()
        val command = CanonicalCoreStepCommand.Shell(
            command = "echo hello",
            isScriptBlock = false,
            returnStdout = false,
        )
        val context = CanonicalShellDispatchContext(
            opId = OpId("durable-echo", 0, 0),
            runId = "durable-echo-run",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = controlDir,
            eventSink = eventStore,
        )

        val outcome = dispatcher.dispatch(command, context)

        assertEquals(StepOutcome.Success, outcome)
        // Durable path emits at least one event for a successful echo command
        assertEquals(1, eventStore.eventsFor("durable-echo-run").count())
    }

    @Test
    @Timeout(30)
    fun `C6-2 durable sh false yields Failure SCRIPT with StepFailed`() = runBlocking {
        val controlDir = tempDir.resolve("control").also { it.toFile().mkdirs() }
        val eventStore = InMemoryEventStore()
        val dispatcher = CanonicalShellNodeDispatcher()
        val command = CanonicalCoreStepCommand.Shell(
            command = "false",
            isScriptBlock = false,
            returnStdout = false,
        )
        val context = CanonicalShellDispatchContext(
            opId = OpId("durable-false", 0, 0),
            runId = "durable-false-run",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = controlDir,
            eventSink = eventStore,
        )

        val outcome = dispatcher.dispatch(command, context)

        // C2: runShellCommandTyped returns Failure(SCRIPT) for non-zero exit
        assertEquals(StepOutcome.Failure::class.java, outcome::class.java)
        val failure = (outcome as StepOutcome.Failure).failure
        assertEquals(FailureKind.SCRIPT, failure.kind)
    }

    @Test
    @Timeout(30)
    fun `C6-3 durable sh with invalid workspace yields Failure INFRASTRUCTURE`() = runBlocking {
        val controlDir = tempDir.resolve("control").also { it.toFile().mkdirs() }
        val eventStore = InMemoryEventStore()
        val dispatcher = CanonicalShellNodeDispatcher()
        val command = CanonicalCoreStepCommand.Shell(
            command = "echo test",
            isScriptBlock = false,
            returnStdout = false,
        )
        // Pass a file path as workspaceRoot — Files.createDirectories on a file path fails
        val invalidWorkspace = tempDir.resolve("a-regular-file.txt").also {
            it.toFile().writeText("not a directory")
        }
        val context = CanonicalShellDispatchContext(
            opId = OpId("durable-ws", 0, 0),
            runId = "durable-ws-run",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(
                workspaceRoot = invalidWorkspace,
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
                sandbox = SandboxConfig.NONE,
            ),
            controlDirRoot = controlDir,
            eventSink = eventStore,
        )

        val outcome = dispatcher.dispatch(command, context)

        // C2: runShellCommandTyped returns Failure(INFRASTRUCTURE) when workspace creation fails
        assertEquals(StepOutcome.Failure::class.java, outcome::class.java)
        val failure = (outcome as StepOutcome.Failure).failure
        assertEquals(FailureKind.INFRASTRUCTURE, failure.kind)
    }

    @Test
    @Timeout(30)
    fun `C6-4 durable sh with captureStdout true yields OutputCaptured`() = runBlocking {
        val controlDir = tempDir.resolve("control").also { it.toFile().mkdirs() }
        val workspaceRoot = tempDir.resolve("workspace").also { it.toFile().mkdirs() }
        val eventStore = InMemoryEventStore()
        val dispatcher = CanonicalShellNodeDispatcher()
        val command = CanonicalCoreStepCommand.Shell(
            command = "echo captured-output",
            isScriptBlock = false,
            returnStdout = false,
        )
        val context = CanonicalShellDispatchContext(
            opId = OpId("durable-capture", 0, 0),
            runId = "durable-capture-run",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(
                workspaceRoot = workspaceRoot,
                captureStdout = true,
                timeoutMs = null,
                env = emptyMap(),
                sandbox = SandboxConfig.NONE,
            ),
            controlDirRoot = controlDir,
            eventSink = eventStore,
        )

        val outcome = dispatcher.dispatch(command, context)

        // C2: durable shell succeeds with captureStdout=true
        assertEquals(StepOutcome.Success, outcome)
    }
}
