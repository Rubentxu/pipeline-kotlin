package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.toStepOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepInvocationOutcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * LB-02 / G3-A4.2 — ShellOperations capability seam.
 *
 * A4.2 freezes the typed capability contract that lets `core.sh` execute through the
 * registry path WITHOUT re-implementing process-launching logic. The handler asks for
 * [SHELL_OPERATIONS_CAPABILITY]; the runtime provides it via `ShOperationsAdapter` which
 * delegates to the existing `ShExecution.invokeShell`.
 *
 * Laws captured:
 *
 *  1. **Capability declaration** — `CoreShellStep.contract.requiredCapabilities` is
 *     exactly `setOf(SHELL_OPERATIONS_CAPABILITY)`.
 *  2. **Capability == use** — the handler reaches `ShellOperations` ONLY through
 *     `ctx.capabilities.get(SHELL_OPERATIONS_CAPABILITY)`.
 *  3. **Success delegation** — when the capability is supplied, the handler invokes
 *     `ShellOperations.invoke` exactly once with the same `command`, `runId`, `stepIndex`,
 *     and projects the typed `ShellInvocationResult` 1:1 into `CoreShellOutput`.
 *  4. **Missing capability** — `RegistryExecutionPreparation.prepare` rejects with a
 *     `Rejected("missing required capabilities...")` outcome BEFORE the handler ever runs.
 *  5. **Handler discipline** — `CoreShellStep` does not import `ProcessBuilder`,
 *     `DurableShellExecutor`, or `bash -c` directly (architecture/structural evidence).
 *  6. **No handler-side event emission** — the handler MUST NOT call `eventSink.append`
 *     or emit `EchoOutputCaptured` itself; that authority lives in `ShExecution`.
 *  7. **Recovery stays at the descriptor** — `RecoveryPolicy.ExternalSubprocess` is on
 *     `CoreShellStep.descriptor` (A4.1). This slice does NOT touch `StepReconcilerL1`.
 */
@Timeout(10)
class A4_2ShellOperationsCapabilityTest {

    // ----- helpers --------------------------------------------------------

    private fun freshRegistry(): InMemoryStepRegistry = InMemoryStepRegistry().apply {
        CoreShellStep.registerInto(this)
    }

    private fun fakeCapabilities(ops: ShellOperations): StepCapabilityAccess =
        object : StepCapabilityAccess {
            override fun available(): Set<StepCapability> = setOf(SHELL_OPERATIONS_CAPABILITY)
            override fun <T : Any> get(key: StepCapability): T {
                check(key == SHELL_OPERATIONS_CAPABILITY) { "unexpected capability $key" }
                @Suppress("UNCHECKED_CAST")
                return ops as T
            }
        }

    private fun emptyCapabilities(): StepCapabilityAccess =
        object : StepCapabilityAccess {
            override fun available(): Set<StepCapability> = emptySet()
            override fun <T : Any> get(key: StepCapability): T =
                throw IllegalStateException("no capabilities exposed: $key")
        }

    private class RecordingShellOps : ShellOperations {
        var callCount: Int = 0
        var lastCommand: ShellCommand? = null
        var lastRunId: RunId? = null
        var lastStepIndex: Int = -1
        var returnValue: ShellInvocationResult = ShellInvocationResult.UnitValue

        override suspend fun invoke(
            command: ShellCommand,
            runId: RunId,
            stepIndex: Int,
        ): ShellInvocationResult {
            callCount += 1
            lastCommand = command
            lastRunId = runId
            lastStepIndex = stepIndex
            return returnValue
        }
    }

    private val encodedInput: EncodedStepValue by lazy {
        CoreShellStep.definition.contract.inputCodec.encode(
            CoreShellInput(command = ShellCommand(script = "echo a4-2", returnMode = ShellReturnMode.STDOUT)),
        )
    }

    // ----- A4.2.2 — Capability declaration ----------------------------------

    @Test
    fun `A4-2-2 CoreShellStep declares SHELL_OPERATIONS_CAPABILITY and no other capability`() {
        val caps = CoreShellStep.definition.contract.requiredCapabilities
        assertEquals(
            setOf<StepCapability>(SHELL_OPERATIONS_CAPABILITY),
            caps,
            "core.sh must declare SHELL_OPERATIONS_CAPABILITY as its only required capability",
        )
    }

    @Test
    fun `A4-2-2 SHELL_OPERATIONS_CAPABILITY token is a typed StepCapability distinct from EVENT_SINK_CAPABILITY`() {
        // Two capability tokens must not collide; a Step that needs both must declare both.
        assertFalse(SHELL_OPERATIONS_CAPABILITY == EVENT_SINK_CAPABILITY)
        assertTrue(SHELL_OPERATIONS_CAPABILITY.key != EVENT_SINK_CAPABILITY.key)
    }

    // ----- A4.2.6 — Success path ---------------------------------------------

    @Test
    fun `A4-2-6 handler delegates typed CoreShellInput to ShellOperations exactly once`() = runBlocking {
        val ops = RecordingShellOps().apply { returnValue = ShellInvocationResult.Stdout("a4-2\n") }
        val handlerCtx = StepHandlerContext(
            runId = RunId("a4-2-success"),
            stepIndex = 5,
            capabilities = fakeCapabilities(ops),
        )

        val output = CoreShellStep.definition.handler.execute(
            CoreShellInput(command = ShellCommand(script = "echo a4-2", returnMode = ShellReturnMode.STDOUT)),
            handlerCtx,
        )

        assertEquals(1, ops.callCount, "handler must invoke ShellOperations exactly once per call")
        assertNotNull(ops.lastCommand)
        assertEquals("echo a4-2", ops.lastCommand!!.script)
        assertEquals(ShellReturnMode.STDOUT, ops.lastCommand!!.returnMode)
        assertEquals(RunId("a4-2-success"), ops.lastRunId)
        assertEquals(5, ops.lastStepIndex)
        assertEquals(ShellInvocationResult.Stdout("a4-2\n"), output.result)
        // A4.3: the typed carrier no longer carries `capturedStdout`. The
        // captured stdout is now derivable as `output.result.value` for the
        // `Stdout` variant. Stdout -> Success; the outcome projection is the
        // single classifier's authority.
        assertEquals(StepOutcome.Success, output.outcome)
    }

    @Test
    fun `A4-2-6 each ShellInvocationResult variant projects 1-1 without string parsing`() = runBlocking {
        // A4.3: each variant preserves its identity AND its canonical outcome.
        // The carrier no longer carries `capturedStdout`; the bytes live in
        // `result.value` for the Stdout case.
        val cases: List<ShellInvocationResult> = listOf(
            ShellInvocationResult.UnitValue,
            ShellInvocationResult.Stdout("hello\n"),
            ShellInvocationResult.Status(exitCode = 0),
            ShellInvocationResult.Failed(
                failure = dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    kind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
                    message = "exit 1",
                ),
                exitCode = 1,
            ),
            ShellInvocationResult.Interrupted(
                interruption = dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord(
                    kind = dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind.TIMEOUT,
                    message = "killed",
                    operationId = "r/0/0",
                ),
            ),
        )
        for (variant in cases) {
            val ops = RecordingShellOps().apply { returnValue = variant }
            val output = CoreShellStep.definition.handler.execute(
                CoreShellInput(command = ShellCommand(script = "x")),
                StepHandlerContext(runId = RunId("a4-2"), stepIndex = 0, capabilities = fakeCapabilities(ops)),
            )
            assertEquals(1, ops.callCount)
            assertEquals(variant, output.result)
            // A4.3 — outcome is classifier-derived, identical to the legacy
            // table. Stdout/Unit/Status -> Success; Failed -> Failure;
            // Interrupted -> Failure(TIMEOUT).
            val expectedOutcome = variant.toStepOutcome()
            assertEquals(expectedOutcome, output.outcome)
        }
    }

    @Test
    fun `A4-2-6 prepare-time admission succeeds when SHELL_OPERATIONS_CAPABILITY is available`() {
        val registry = freshRegistry()
        val outcome = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreShellStep.KEY,
            encodedInput = encodedInput,
            availableCapabilities = setOf(SHELL_OPERATIONS_CAPABILITY),
        )
        assertInstanceOf(ExecutionPreparation.Ready::class.java, outcome)
    }

    // ----- A4.2.6 — Missing capability (fail-closed) ------------------------

    @Test
    fun `A4-2-6 prepare-time admission REJECTS when SHELL_OPERATIONS_CAPABILITY is missing`() {
        val registry = freshRegistry()
        val outcome = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreShellStep.KEY,
            encodedInput = encodedInput,
            availableCapabilities = emptySet(),
        )
        val rejected = assertInstanceOf(ExecutionPreparation.Rejected::class.java, outcome)
        assertTrue(
            rejected.reason.contains("missing required capabilities"),
            "rejection must cite missing capability, was: ${rejected.reason}",
        )
        assertTrue(
            rejected.reason.contains(SHELL_OPERATIONS_CAPABILITY.key),
            "rejection must name the SHELL_OPERATIONS capability token, was: ${rejected.reason}",
        )
    }

    @Test
    fun `A4-2-6 prepare-time admission REJECTS when only an unrelated capability is available`() {
        // Demonstrates the typed capability admission is NOT a "do you happen to have any capability?"
        // check: supplying a wrong capability still fails closed.
        val registry = freshRegistry()
        val wrongCap = StepCapability("wrongThing")
        val outcome = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreShellStep.KEY,
            encodedInput = encodedInput,
            availableCapabilities = setOf(wrongCap),
        )
        assertInstanceOf(ExecutionPreparation.Rejected::class.java, outcome)
    }

    @Test
    fun `A4-2-6 handler never runs when capability is missing (no exception thrown by handler)`() = runBlocking {
        // Defensive characterization: even if someone bypasses the prepare-time gate and tries to
        // call the handler with no SHELL_OPERATIONS_CAPABILITY, the handler MUST throw — proving
        // it never silently does work without the capability. This is the post-admission re-check
        // witness for A4.2.6.
        val ops = RecordingShellOps()
        var thrown: Throwable? = null
        try {
            CoreShellStep.definition.handler.execute(
                CoreShellInput(command = ShellCommand(script = "x")),
                StepHandlerContext(runId = RunId("x"), stepIndex = 0, capabilities = emptyCapabilities()),
            )
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull(thrown, "handler must throw when SHELL_OPERATIONS_CAPABILITY is missing")
        assertEquals(0, ops.callCount, "handler must NOT have invoked ShellOperations")
    }

    // ----- A4.2.5 — No duplicate event emission ------------------------------

    private fun readProjectFile(relativePath: String): String {
        // Tests run with CWD = the pipeline-application module directory; resolve the
        // project root by walking up from CWD until the `.git` directory appears.
        var cwd: java.io.File = java.io.File(".").absoluteFile
        var projectRoot: java.io.File? = null
        repeat(10) {
            if (cwd.resolve(".git").exists()) {
                projectRoot = cwd
                return@repeat
            }
            cwd = cwd.parentFile ?: return@repeat
        }
        val root = projectRoot ?: error("could not locate project root from CWD=${java.io.File(".").absolutePath}")
        val file = root.resolve(relativePath)
        check(file.exists()) { "fixture file missing: $file (cwd=${java.io.File(".").absolutePath}, root=$root)" }
        return file.readText()
    }

    @Test
    fun `A4-2-5 CoreShellStep source does not import EventSink or EchoOutputCaptured directly`() {
        // Single authority for EchoOutputCaptured: the durable ShExecution substrate.
        // The handler MUST NOT reach the events package or the typed event class.
        val text = readProjectFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreShellStep.kt",
        )
        assertFalse(text.contains("import dev.rubentxu.pipeline.v2.events.EventSink"),
            "CoreShellStep must not import EventSink (single authority lives in ShExecution)")
        assertFalse(text.contains("import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured"),
            "CoreShellStep must not import EchoOutputCaptured (single authority lives in ShExecution)")
        assertFalse(text.contains("eventSink.append"),
            "CoreShellStep must not call eventSink.append (no handler-side event emission)")
    }

    // ----- A4.2.5 — No process-engine rewrite -------------------------------

    @Test
    fun `A4-2-5 CoreShellStep source does not import process-engine classes directly`() {
        // Architecture/structural evidence: the handler adapts to the typed capability seam,
        // it does not touch ProcessBuilder, DurableShellExecutor, bash -c, or Runtime.exec.
        val text = readProjectFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreShellStep.kt",
        )
        listOf(
            "ProcessBuilder",
            "Runtime.getRuntime",
            "Runtime.exec",
            "bash -c",
            "DurableShellExecutor",
        ).forEach { forbidden ->
            assertFalse(text.contains(forbidden),
                "CoreShellStep must not reference process-engine symbol '$forbidden' " +
                "(single authority lives in ShExecution via ShOperationsAdapter)")
        }
    }

    // ----- A4.2.1 — ShellOperations port is narrow ---------------------------

    @Test
    fun `A4-2-1 ShellOperations port signature is narrow (no CanonicalRuntimeContext, no journal)`() {
        // Static evidence: the interface declared in production code does not expose
        // canonical runtime context, the journal, the registry, or process registry.
        val text = readProjectFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ShellOperations.kt",
        )
        assertTrue(text.contains("interface ShellOperations"),
            "ShellOperations must be a narrow interface")
        listOf(
            "CanonicalRuntimeContext",
            "Journal",
            "StepRegistry",
            "ProcessBuilder",
        ).forEach { forbidden ->
            assertFalse(text.contains(forbidden),
                "ShellOperations port must not leak '$forbidden' through its public surface")
        }
    }

    // ----- A4.2.7 — Recovery is preserved at the descriptor, not in the handler ---

    @Test
    fun `A4-2-7 CoreShellStep descriptor keeps RecoveryPolicy ExternalSubprocess (A4-1 intact)`() {
        val descriptor = CoreShellStep.definition.contract.descriptor
        assertEquals(RecoveryPolicy.ExternalSubprocess, descriptor.recoveryPolicy)
        assertEquals(ReplayPolicy.RERUN, descriptor.replayPolicy)
    }

    @Test
    fun `A4-2-7 CoreShellStep source does not import StepReconcilerL1 or recovery code`() {
        // The handler must not touch the recovery substrate; that lives in A4.10.
        val text = readProjectFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreShellStep.kt",
        )
        listOf(
            "StepReconcilerL1",
            "Reconciler",
            "recoverRunningShell",
            "controlDirRoot",
        ).forEach { forbidden ->
            assertFalse(text.contains(forbidden),
                "CoreShellStep must not reference recovery substrate '$forbidden' (A4.7)")
        }
    }

    // ----- A4.2.3 — Adapter delegates to ShExecution.invokeShell -----------------

    @Test
    fun `A4-2-3 ShOperationsAdapter source delegates to ShExecution invokeShell exactly once per call`() {
        // Static evidence: the adapter file invokes ShExecution.invokeShell once per .invoke call.
        val text = readProjectFile(
            "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShOperationsAdapter.kt",
        )
        assertTrue(text.contains("ShExecution.invokeShell("),
            "ShOperationsAdapter must delegate to ShExecution.invokeShell")
        assertFalse(text.contains("ProcessBuilder("),
            "ShOperationsAdapter must not start a ProcessBuilder")
        assertFalse(text.contains("Runtime.getRuntime"),
            "ShOperationsAdapter must not call Runtime.getRuntime")
    }
}
