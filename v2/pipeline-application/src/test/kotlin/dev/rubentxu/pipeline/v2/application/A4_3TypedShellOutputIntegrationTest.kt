package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionResult
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.toStepOutcome
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * LB-02 / G3-A4.3 — Typed `ShellOutput` carrier integration.
 *
 * Proves end-to-end that:
 *  - `core.sh` handler returns a typed `CoreShellOutput(result, outcome)` (A4.3.6).
 *  - `outcome` is computed via the public `toStepOutcome()` classifier
 *    (A4.3.4) and is byte-equivalent to the legacy table.
 *  - `CommonExecutionBoundary` projects `outcome` from `produced as? TypedStepOutput`
 *    (A4.3.10) without branching on `core.sh` itself.
 *  - The handler does NOT reach `EventSink` / `EchoOutputCaptured` directly
 *    (A4.3.8): the only authority for shell observability is
 *    `ShExecution.invokeShell`.
 *  - The output codec encodes `kind` + `outcome` losslessly and deterministically
 *    (A4.3.5), so the durable layer can replay-decode through the
 *    same codec.
 *
 * `core.sh` is NOT yet `REGISTRY_PRIMARY`; these tests prove the registry seam
 * works in isolation against the boundary and the classifier. They are the
 * A4.3 acceptance suite; flipping `core.sh = REGISTRY_PRIMARY` is gated on a
 * later slice once legacy + registry parity is frozen.
 */
@Timeout(10)
class A4_3TypedShellOutputIntegrationTest {

    // ----- Test fixtures ---------------------------------------------------

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

    private fun runtimeContext(runId: String = "a4-3"): CanonicalRuntimeContext =
        CanonicalRuntimeContext(
            opId = OpId(runId, 0, 0),
            runId = runId,
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = null,
            eventSink = InMemoryEventStore(),
        )

    private fun shellOpsReturning(value: ShellInvocationResult): RecordingShellOps =
        RecordingShellOps().apply { returnValue = value }

    private fun fakeCapabilities(ops: RecordingShellOps): StepCapabilityAccess =
        object : StepCapabilityAccess {
            override fun <T : Any> get(capability: StepCapability): T {
                @Suppress("UNCHECKED_CAST")
                return ops as T
            }

            override fun available(): Set<StepCapability> = setOf(SHELL_OPERATIONS_CAPABILITY)
        }

    private fun readyPreparedForCoreSh(
        registry: InMemoryStepRegistry,
        access: StepCapabilityAccess,
        input: CoreShellInput,
    ): PreparedExecution {
        val prep = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreShellStep.KEY,
            encodedInput = CoreShellStep.definition.contract.inputCodec.encode(input),
            availableCapabilities = access.available(),
        )
        return when (prep) {
            is ExecutionPreparation.Ready -> prep.prepared
            is ExecutionPreparation.Rejected -> error("prepare rejected core.sh: ${prep.reason}")
        }
    }

    // ----- A4.3.4 — Classifier authority (legacy == registry) ---------------

    @Test
    fun `classifier Unit maps to Success (matches legacy table)`() {
        assertEquals(StepOutcome.Success, ShellInvocationResult.UnitValue.toStepOutcome())
    }

    @Test
    fun `classifier Stdout maps to Success (matches legacy table)`() {
        val r = ShellInvocationResult.Stdout("hello\n")
        assertEquals(StepOutcome.Success, r.toStepOutcome())
    }

    @Test
    fun `classifier Status maps to Success (matches legacy table)`() {
        val r = ShellInvocationResult.Status(exitCode = 0)
        assertEquals(StepOutcome.Success, r.toStepOutcome())
        // Even non-zero exit: returnMode==STATUS always classifies as Success
        // for the registry; the Step itself decides further via the encoded
        // `exitCode` field. This mirrors the legacy convention used by
        // `runShellCommandTyped`.
        val nonzero = ShellInvocationResult.Status(exitCode = 7)
        assertEquals(StepOutcome.Success, nonzero.toStepOutcome())
    }

    @Test
    fun `classifier Failed maps to Failure carrying the typed PipelineFailure`() {
        val pf = PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 7")
        val r = ShellInvocationResult.Failed(failure = pf, exitCode = 7)
        val outcome = r.toStepOutcome()
        assertTrue(outcome is StepOutcome.Failure, "Failed MUST map to Failure")
        val carried = (outcome as StepOutcome.Failure).failure
        assertEquals(FailureKind.SCRIPT, carried.kind)
        assertEquals("exit 7", carried.message)
    }

    @Test
    fun `classifier Interrupted maps to Failure TIMEOUT (legacy convention)`() {
        val r = ShellInvocationResult.Interrupted(
            interruption = InterruptionRecord(
                kind = InterruptionKind.TIMEOUT,
                message = "killed by timeout",
                operationId = "run/0/0",
            ),
        )
        val outcome = r.toStepOutcome()
        assertTrue(outcome is StepOutcome.Failure)
        val carried = (outcome as StepOutcome.Failure).failure
        assertEquals(FailureKind.TIMEOUT, carried.kind)
        assertEquals("killed by timeout", carried.message)
    }

    // ----- A4.3.6 — Handler returns typed carrier ----------------------------

    @Test
    fun `handler exit-0 Stdout returns CoreShellOutput with outcome=Success`() = runBlocking {
        val ops = shellOpsReturning(ShellInvocationResult.Stdout("hello\n"))
        val output = CoreShellStep.definition.handler.execute(
            CoreShellInput(command = ShellCommand(script = "echo hello", returnMode = ShellReturnMode.STDOUT)),
            StepHandlerContext(runId = RunId("a4-3"), stepIndex = 0, capabilities = fakeCapabilities(ops)),
        )
        assertEquals(ShellInvocationResult.Stdout("hello\n"), output.result)
        assertEquals(StepOutcome.Success, output.outcome)
        assertTrue(output is TypedStepOutput, "CoreShellOutput MUST implement TypedStepOutput")
    }

    @Test
    fun `handler Failed exit returns CoreShellOutput with outcome=Failure SCRIPT`() = runBlocking {
        val r = ShellInvocationResult.Failed(
            failure = PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 1"),
            exitCode = 1,
        )
        val ops = shellOpsReturning(r)
        val output = CoreShellStep.definition.handler.execute(
            CoreShellInput(command = ShellCommand(script = "false")),
            StepHandlerContext(runId = RunId("a4-3"), stepIndex = 0, capabilities = fakeCapabilities(ops)),
        )
        assertEquals(r, output.result)
        assertTrue(output.outcome is StepOutcome.Failure)
        val f = (output.outcome as StepOutcome.Failure).failure
        assertEquals(FailureKind.SCRIPT, f.kind)
        assertEquals("exit 1", f.message)
    }

    @Test
    fun `handler Interrupted maps to Failure TIMEOUT on the typed carrier`() = runBlocking {
        val interruption = InterruptionRecord(
            kind = InterruptionKind.PARENT_CANCELLED,
            message = "cancelled",
            operationId = "run/0/0",
        )
        val r = ShellInvocationResult.Interrupted(interruption = interruption)
        val ops = shellOpsReturning(r)
        val output = CoreShellStep.definition.handler.execute(
            CoreShellInput(command = ShellCommand(script = "sleep 999")),
            StepHandlerContext(runId = RunId("a4-3"), stepIndex = 0, capabilities = fakeCapabilities(ops)),
        )
        assertEquals(r, output.result)
        assertTrue(output.outcome is StepOutcome.Failure)
        val f = (output.outcome as StepOutcome.Failure).failure
        // The classifier collapses any interruption into TIMEOUT (legacy convention);
        // the original InterruptionKind survives on `result.interruption.kind` for
        // any consumer that needs to recover it.
        assertEquals(FailureKind.TIMEOUT, f.kind)
        assertEquals(InterruptionKind.PARENT_CANCELLED, interruption.kind)
    }

    // ----- A4.3.5 — Output codec lossless + deterministic + outcome projection

    @Test
    fun `codec UNIT encodes kind=UNIT and outcome=SUCCESS losslessly`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.UnitValue,
            outcome = StepOutcome.Success,
        )
        val encoded = CoreShellStep.definition.contract.outputCodec.encode(out)
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("UNIT", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("SUCCESS", obj["outcome"]?.jsonPrimitive?.content)
    }

    @Test
    fun `codec STDOUT encodes value and outcome=SUCCESS deterministically`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.Stdout("hi\n"),
            outcome = StepOutcome.Success,
        )
        val encoded = CoreShellStep.definition.contract.outputCodec.encode(out)
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("STDOUT", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("SUCCESS", obj["outcome"]?.jsonPrimitive?.content)
        assertEquals("hi\n", obj["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `codec FAILED encodes failureKind message exitCode outcome=FAILURE`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.Failed(
                failure = PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 7"),
                exitCode = 7,
            ),
            outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 7")),
        )
        val encoded = CoreShellStep.definition.contract.outputCodec.encode(out)
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("FAILED", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("FAILURE", obj["outcome"]?.jsonPrimitive?.content)
        assertEquals("SCRIPT", obj["failureKind"]?.jsonPrimitive?.content)
        // G7: failure message is encoded under `failureMessage` to disambiguate
        // from `interruptionMessage` on the INTERRUPTED variant.
        assertEquals("exit 7", obj["failureMessage"]?.jsonPrimitive?.content)
        assertEquals("7", obj["exitCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `codec STATUS encodes exitCode and outcome=SUCCESS`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.Status(exitCode = 0),
            outcome = StepOutcome.Success,
        )
        val encoded = CoreShellStep.definition.contract.outputCodec.encode(out)
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("STATUS", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("SUCCESS", obj["outcome"]?.jsonPrimitive?.content)
        assertEquals("0", obj["exitCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `codec INTERRUPTED encodes interruptionKind message operationId outcome=FAILURE`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.Interrupted(
                interruption = InterruptionRecord(
                    kind = InterruptionKind.TIMEOUT,
                    message = "killed",
                    operationId = "r/0/0",
                ),
            ),
            outcome = StepOutcome.Failure(PipelineFailure(kind = FailureKind.TIMEOUT, message = "killed")),
        )
        val encoded = CoreShellStep.definition.contract.outputCodec.encode(out)
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("INTERRUPTED", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("FAILURE", obj["outcome"]?.jsonPrimitive?.content)
        assertEquals("TIMEOUT", obj["interruptionKind"]?.jsonPrimitive?.content)
        // G7: interruption message is encoded under `interruptionMessage` to
        // disambiguate from `failureMessage` on the FAILED variant.
        assertEquals("killed", obj["interruptionMessage"]?.jsonPrimitive?.content)
        assertNotNull(obj["operationId"])
    }

    @Test
    fun `codec encode is deterministic (same input means same bytes)`() {
        val out = CoreShellOutput(
            result = ShellInvocationResult.Stdout("hi"),
            outcome = StepOutcome.Success,
        )
        val first = CoreShellStep.definition.contract.outputCodec.encode(out).value
        val second = CoreShellStep.definition.contract.outputCodec.encode(out).value
        assertEquals(first, second, "encode MUST be deterministic for the same input")
    }

    @Test
    fun `codec does NOT re-encode capturedStdout or durationMs from prior carrier`() {
        // A4.3 invariant: the A4.2 carrier carried capturedStdout/durationMs; the A4.3
        // carrier does NOT. If either sneaks back into the encoded form, it is a
        // contract regression and MUST be caught.
        val out = CoreShellOutput(
            result = ShellInvocationResult.Stdout("hello"),
            outcome = StepOutcome.Success,
        )
        val obj = Json.parseToJsonElement(
            CoreShellStep.definition.contract.outputCodec.encode(out).value,
        ).jsonObject
        assertTrue("capturedStdout" !in obj.keys, "capturedStdout must not be re-encoded")
        assertTrue("durationMs" !in obj.keys, "durationMs must not be re-encoded")
    }

    // ----- A4.3.10 — Boundary projects outcome via TypedStepOutput -----------
    //
    // These tests do NOT drive `core.sh` end-to-end through the boundary (the
    // canonical runtime's `CanonicalRuntimeCapabilityAccess` only provides
    // `EVENT_SINK_CAPABILITY`; wiring `SHELL_OPERATIONS_CAPABILITY` is the
    // production runtime's concern, exercised by the integration UAT at
    // `compatibility/`). Instead, they prove the projection rule directly:
    // the boundary's `(produced as? TypedStepOutput)?.outcome` MUST be true
    // for any handler that returns a `TypedStepOutput`, regardless of the
    // concrete Step.

    private fun typedStepDefinition(
        key: PluginStepId,
        outcome: StepOutcome,
        encoded: String = "ok",
    ): StepDefinition<String, CoreShellOutput> {
        val codec = object : StepCodec<String> {
            override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
            override fun decode(encoded: EncodedStepValue): String = encoded.value
        }
        return object : StepDefinition<String, CoreShellOutput> {
            override val contract: StepContract<String, CoreShellOutput> = StepContract(
                key = key,
                descriptor = StepDescriptor(
                    stepId = key.value,
                    name = key.value,
                    configRef = "",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.RERUN,
                ),
                inputCodec = codec,
                outputCodec = CoreShellStep.definition.contract.outputCodec,
                requiredCapabilities = emptySet(),
            )

            override val handler: StepHandler<String, CoreShellOutput> =
                StepHandler { _, _ ->
                    CoreShellOutput(
                        result = ShellInvocationResult.Stdout(encoded),
                        outcome = outcome,
                    )
                }
        }
    }

    private fun readyPrepared(
        registry: InMemoryStepRegistry,
        key: PluginStepId,
        access: StepCapabilityAccess,
    ): PreparedExecution {
        val prep = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = key,
            encodedInput = EncodedStepValue("ignored"),
            availableCapabilities = access.available(),
        )
        return when (prep) {
            is ExecutionPreparation.Ready -> prep.prepared
            is ExecutionPreparation.Rejected -> error("prepare rejected ${key.value}: ${prep.reason}")
        }
    }

    private fun canonicalAccess(runId: String): CanonicalRuntimeCapabilityAccess =
        CanonicalRuntimeCapabilityAccess(runtimeContext(runId))

    @Test
    fun `boundary projects outcome=SUCCESS from a TypedStepOutput carrier`() = runBlocking {
        val key = PluginStepId("test.typed.success")
        val registry = InMemoryStepRegistry().apply { register(typedStepDefinition(key, StepOutcome.Success)) }
        val access = canonicalAccess("a4-3-boundary-success")
        val prepared = readyPrepared(registry, key, access)
        val result = RegistryExecutionBoundary.adapt().execute(prepared, runtimeContext("a4-3-boundary-success"))
        assertEquals(StepOutcome.Success, result.outcome)
        assertNotNull(result.encodedOutput)
        val obj = Json.parseToJsonElement(result.encodedOutput!!.value).jsonObject
        assertEquals("STDOUT", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("SUCCESS", obj["outcome"]?.jsonPrimitive?.content)
    }

    @Test
    fun `boundary projects outcome=FAILURE from a TypedStepOutput carrier`() = runBlocking {
        val key = PluginStepId("test.typed.failure")
        val failure = PipelineFailure(kind = FailureKind.SCRIPT, message = "exit 1")
        val registry = InMemoryStepRegistry().apply {
            register(
                typedStepDefinition(
                    key,
                    StepOutcome.Failure(failure),
                    encoded = "exit 1",
                ),
            )
        }
        val access = canonicalAccess("a4-3-boundary-failure")
        val prepared = readyPrepared(registry, key, access)
        val result = RegistryExecutionBoundary.adapt().execute(prepared, runtimeContext("a4-3-boundary-failure"))
        assertTrue(result.outcome is StepOutcome.Failure)
        val carried = (result.outcome as StepOutcome.Failure).failure
        assertEquals(FailureKind.SCRIPT, carried.kind)
        assertEquals("exit 1", carried.message)
    }

    @Test
    fun `boundary defaults to outcome=SUCCESS for handlers returning a non-TypedStepOutput value`() = runBlocking {
        // A4.3 invariant: handlers that return a non-`TypedStepOutput` carrier
        // (e.g. `String` for echo-like Steps) MUST still produce a result.
        // The boundary defaults to `Success` because there is no typed outcome
        // to project — this preserves the legacy echo + string-output shape.
        val key = PluginStepId("test.non.typed")
        val codec = object : StepCodec<String> {
            override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
            override fun decode(encoded: EncodedStepValue): String = encoded.value
        }
        val def = object : StepDefinition<String, String> {
            override val contract: StepContract<String, String> = StepContract(
                key = key,
                descriptor = StepDescriptor(
                    stepId = key.value,
                    name = key.value,
                    configRef = "",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.RERUN,
                ),
                inputCodec = codec,
                outputCodec = codec,
                requiredCapabilities = emptySet(),
            )

            override val handler: StepHandler<String, String> = StepHandler { _, _ -> "ok" }
        }
        val registry = InMemoryStepRegistry().apply { register(def) }
        val access = canonicalAccess("a4-3-boundary-nontyped")
        val prepared = readyPrepared(registry, key, access)
        val result = RegistryExecutionBoundary.adapt().execute(prepared, runtimeContext("a4-3-boundary-nontyped"))
        assertEquals(StepOutcome.Success, result.outcome)
        assertNotNull(result.encodedOutput)
        assertEquals("ok", result.encodedOutput!!.value)
    }

    @Test
    fun `boundary handler exception surfaces as outcome=FAILURE ENGINE without leaking typed carrier`() = runBlocking {
        // A4.3 boundary invariant: when the handler throws, the boundary MUST
        // still produce a CommonExecutionResult (it is the exception-to-result
        // adapter), but the typed carrier is null because the handler never
        // returned. Outcome is classified by the boundary itself, NOT by the
        // classifier — the classifier only runs on successful handler returns.
        val registry = InMemoryStepRegistry()
        val blowingKey = PluginStepId("test.blow")
        val codec = object : StepCodec<String> {
            override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
            override fun decode(encoded: EncodedStepValue): String = encoded.value
        }
        registry.register(
            object : StepDefinition<String, String> {
                override val contract: StepContract<String, String> = StepContract(
                    key = blowingKey,
                    descriptor = StepDescriptor(
                        stepId = blowingKey.value,
                        name = "blow",
                        configRef = "",
                        executionLocation = ExecutionLocation.CONTROLLER,
                        effects = listOf(Effect.READ_ONLY),
                        replayPolicy = ReplayPolicy.RERUN,
                    ),
                    inputCodec = codec,
                    outputCodec = codec,
                    requiredCapabilities = emptySet(),
                )

                override val handler: StepHandler<String, String> = StepHandler { _, _ ->
                    throw IllegalStateException("blowing up on purpose")
                }
            },
        )

        val prep = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = blowingKey,
            encodedInput = EncodedStepValue("ignored"),
            availableCapabilities = emptySet(),
        )
        val prepared = when (prep) {
            is ExecutionPreparation.Ready -> prep.prepared
            is ExecutionPreparation.Rejected -> error("prepare rejected blowing test: ${prep.reason}")
        }

        val result = RegistryExecutionBoundary.adapt().execute(prepared, runtimeContext("a4-3-throw"))
        assertTrue(result.outcome is StepOutcome.Failure)
        val f = (result.outcome as StepOutcome.Failure).failure
        assertEquals(FailureKind.ENGINE, f.kind)
        assertNull(result.encodedOutput, "thrown handler produces no encodedOutput")
    }

    // ----- A4.3.8 — Handler does NOT emit observability directly ------------

    @Test
    fun `handler does NOT import EventSink or EchoOutputCaptured directly`() {
        // Handler discipline: the only authority for shell observability is
        // ShExecution.invokeShell. The handler source MUST NOT reference
        // EventSink or EchoOutputCaptured in CODE — those references belong
        // to the substrate. Comments/KDoc describing the rule are fine; the
        // assertion strips them out first.
        val raw = java.io.File(
            "src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreShellStep.kt",
        ).readText()
        val codeOnly = raw
            .lineSequence()
            .map { line -> line.substringBefore("//") }
            .joinToString("\n")
            // Strip block comments /* ... */ naïvely (single-pass, sufficient
            // because our KDoc has no nested comments).
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(
            "EventSink" !in codeOnly,
            "CoreShellStep MUST NOT reference EventSink directly (single observability authority = ShExecution)",
        )
        assertTrue(
            "EchoOutputCaptured" !in codeOnly,
            "CoreShellStep MUST NOT reference EchoOutputCaptured directly",
        )
        assertTrue(
            "runBlocking" !in codeOnly,
            "CoreShellStep MUST NOT bridge suspend through runBlocking",
        )
        assertTrue(
            "GlobalScope" !in codeOnly,
            "CoreShellStep MUST NOT launch detached coroutines",
        )
    }
}
