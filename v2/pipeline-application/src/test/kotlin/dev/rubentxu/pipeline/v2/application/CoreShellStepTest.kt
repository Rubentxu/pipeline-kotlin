package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * LB-02 / G1 registry seam proof: `core.sh` is wired behind the open
 * [dev.rubentxu.pipeline.v2.domain.step.StepRegistry] surface.
 *
 * Scope: prove the typed I/O shape, codec round-trip, key uniqueness, and
 * required-capabilities contract all hold WITHOUT depending on
 * `ShExecution.invokeShell` or the legacy canonical-core decode/dispatch
 * path. Real sh launch lands at G3 with the `SHELL_OPERATIONS` capability.
 *
 * Per AGENTS.md §STEP IMPLEMENTATION — OPERATIVE GUIDE rule 9, all rows in
 * this class mirror the [EchoStepContractSuiteTest] row-for-row, but for sh.
 */
@Timeout(15)
class CoreShellStepTest {

    private fun freshRegistry() = InMemoryStepRegistry().apply {
        CoreShellStep.registerInto(this)
    }

    private fun fakeCapabilities(ops: ShellOperations): StepCapabilityAccess = object : StepCapabilityAccess {
        override fun available(): Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability> = setOf(SHELL_OPERATIONS_CAPABILITY)
        override fun <T : Any> get(key: dev.rubentxu.pipeline.v2.domain.step.StepCapability): T {
            check(key == SHELL_OPERATIONS_CAPABILITY) { "unexpected capability $key" }
            @Suppress("UNCHECKED_CAST")
            return ops as T
        }
    }

    private fun shellOpsReturning(result: ShellInvocationResult) = object : ShellOperations {
        var callCount = 0
        var lastCommand: ShellCommand? = null
        var lastRunId: RunId? = null
        var lastStepIndex: Int = -1
        override suspend fun invoke(
            command: dev.rubentxu.pipeline.v2.domain.ShellCommand,
            runId: RunId,
            stepIndex: Int,
        ): ShellInvocationResult {
            callCount += 1
            lastCommand = command
            lastRunId = runId
            lastStepIndex = stepIndex
            return result
        }
    }

    @Test
    fun `identity — KEY is core dot sh and unique within the registry`() {
        assertEquals(PluginStepId("core.sh"), CoreShellStep.KEY)
        assertEquals("core.sh", CoreShellStep.KEY.value)
        // Re-registering must fail (deterministic / idempotent error).
        val r = freshRegistry()
        assertTrue(
            runCatching { CoreShellStep.registerInto(r) }.isFailure,
            "duplicate registration of core.sh must fail",
        )
    }

    @Test
    fun `contract completeness — key, descriptor, input codec, output codec, required capabilities`() {
        val contract = CoreShellStep.definition.contract
        assertEquals(CoreShellStep.KEY, contract.key)
        val d: StepDescriptor = contract.descriptor
        assertEquals("core.sh", d.stepId)
        assertEquals("sh", d.name)
        assertEquals(ExecutionLocation.AGENT, d.executionLocation)
        assertEquals(listOf(Effect.EXECUTES_SUBPROCESS), d.effects)
        assertEquals(ReplayPolicy.RERUN, d.replayPolicy)
        assertNotNull(contract.inputCodec)
        assertNotNull(contract.outputCodec)
        // G3-A4.2: capability declaration. The handler reaches shell execution
        // ONLY through SHELL_OPERATIONS_CAPABILITY; admission is fail-closed.
        assertEquals(
            setOf<dev.rubentxu.pipeline.v2.domain.step.StepCapability>(SHELL_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
        )
    }

    @Test
    fun `codec input — encode and round-trip preserve script and returnMode`() {
        val input = CoreShellInput(
            command = ShellCommand(
                script = "echo hello",
                encoding = "utf-8",
                label = "g1",
                returnMode = ShellReturnMode.STDOUT,
            ),
        )
        val encoded: EncodedStepValue = CoreShellStep.definition.contract.inputCodec.encode(input)
        assertEquals(
            """{"kind":"shell","script":"echo hello","encoding":"utf-8","label":"g1","returnMode":"STDOUT"}""",
            encoded.value,
            "input codec must emit the canonical dsl-v1 envelope",
        )
        val decoded = CoreShellStep.definition.contract.inputCodec.decode(encoded)
        assertEquals("echo hello", decoded.command.script)
        assertEquals("utf-8", decoded.command.encoding)
        assertEquals("g1", decoded.command.label)
        assertEquals(ShellReturnMode.STDOUT, decoded.command.returnMode)
    }

    @Test
    fun `codec input — decode rejects a non-shell payload kind`() {
        val foreign = EncodedStepValue("""{"kind":"not-shell","script":"x","returnMode":"NONE"}""")
        assertTrue(
            runCatching { CoreShellStep.definition.contract.inputCodec.decode(foreign) }.isFailure,
            "decode must fail closed on a non-shell payload kind",
        )
    }

    @Test
    fun `codec output — encode emits well-formed JSON object with the variant discriminant`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.UnitValue,
            capturedStdout = "",
            durationMs = 7L,
        )
        val encoded = CoreShellStep.definition.contract.outputCodec.encode(out)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is kotlinx.serialization.json.JsonObject, "output envelope must be a JSON object")
        val obj = parsed as kotlinx.serialization.json.JsonObject
        assertEquals("UNIT", obj["kind"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("", obj["capturedStdout"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("7", obj["durationMs"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `codec output — Stdout variant encodes value field`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.Stdout("hello\n"),
            capturedStdout = "hello\n",
            durationMs = 12L,
        )
        val encoded = CoreShellStep.definition.contract.outputCodec.encode(out)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is kotlinx.serialization.json.JsonObject)
        val obj = parsed as kotlinx.serialization.json.JsonObject
        assertEquals("STDOUT", obj["kind"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("hello\n", obj["value"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `codec output — Failed variant encodes failureKind and message`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.Failed(
                failure = dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    kind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
                    message = "exit 7",
                ),
                exitCode = 7,
            ),
            capturedStdout = "",
            durationMs = 0L,
        )
        val encoded = CoreShellStep.definition.contract.outputCodec.encode(out)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value) as kotlinx.serialization.json.JsonObject
        assertEquals("FAILED", obj["kind"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("SCRIPT", obj["failureKind"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("exit 7", obj["message"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("7", obj["exitCode"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `registry resolution — production factory does NOT yet contain core sh (G3 will flip)`() {
        // G1: factory still seeds only CoreEchoStep. Including CoreShellStep is the G3 flip.
        val r = CoreStepRegistryFactory.registry()
        assertTrue(r.contains(dev.rubentxu.pipeline.v2.domain.PluginStepId("core.echo")))
        assertEquals(false, r.contains(CoreShellStep.KEY))
    }

    @Test
    fun `handler — capability-routed handler invokes ShellOperations exactly once and projects typed result`() = runBlocking {
        // A4.2 success path: the typed CoreShellInput reaches the ShellOperations seam once
        // with the same payload and execution identity; the typed ShellInvocationResult
        // returned is projected 1:1 into the typed CoreShellOutput (no string parsing).
        val ops = shellOpsReturning(ShellInvocationResult.Stdout("hello\n"))
        val def = CoreShellStep.definition
        val input = CoreShellInput(command = ShellCommand(script = "echo hello", returnMode = ShellReturnMode.STDOUT))
        val output = def.handler.execute(
            input,
            StepHandlerContext(runId = RunId("a4-2"), stepIndex = 7, capabilities = fakeCapabilities(ops)),
        )

        assertEquals(1, ops.callCount)
        assertEquals("echo hello", ops.lastCommand!!.script)
        assertEquals(ShellReturnMode.STDOUT, ops.lastCommand!!.returnMode)
        assertEquals(RunId("a4-2"), ops.lastRunId)
        assertEquals(7, ops.lastStepIndex)
        assertEquals(ShellInvocationResult.Stdout("hello\n"), output.result)
        assertEquals("hello\n", output.capturedStdout)
        assertEquals(0L, output.durationMs)
    }

    @Test
    fun `handler — non-Stdout variants project with empty capturedStdout and the typed result preserved`() = runBlocking {
        // The handler is a 1:1 projection of the closed ShellInvocationResult ADT.
        // Stdout carries captured stdout; the other variants carry no captured stdout
        // (them being typed semantics, not captured bytes). The adapter remains the
        // single authority for EchoOutputCaptured events.
        val cases = listOf(
            ShellInvocationResult.UnitValue,
            ShellInvocationResult.Status(exitCode = 0),
            ShellInvocationResult.Failed(
                failure = dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    kind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
                    message = "exit 7",
                ),
                exitCode = 7,
            ),
            ShellInvocationResult.Interrupted(
                interruption = dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord(
                    kind = dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind.TIMEOUT,
                    message = "killed",
                    operationId = "a/b/0",
                ),
            ),
        )
        for (variant in cases) {
            val ops = shellOpsReturning(variant)
            val output = CoreShellStep.definition.handler.execute(
                CoreShellInput(command = ShellCommand(script = "x")),
                StepHandlerContext(runId = RunId("a4-2"), stepIndex = 0, capabilities = fakeCapabilities(ops)),
            )
            assertEquals(1, ops.callCount)
            assertEquals(variant, output.result)
            assertEquals("", output.capturedStdout)
        }
    }

    @Test
    fun `canonical envelope — emitted envelope is a well-formed JSON object (durable eligibility)`() {
        val input = CoreShellInput(command = ShellCommand(script = "echo durable-eligible"))
        val encoded = CoreShellStep.definition.contract.inputCodec.encode(input)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
        assertTrue(parsed is kotlinx.serialization.json.JsonObject, "canonical sh envelope must be a JSON object")
    }
}
