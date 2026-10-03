package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * S4-A0 — characterization of the scripted RESTORE path, the half that reads
 * durable state rather than executing it.
 *
 * ## Why this suite exists
 *
 * `ScriptedRegistryInvoker.invoke` is well covered on the fresh and reuse paths
 * by `ScriptedRegistryInvokerTest`. What is NOT covered is what happens when the
 * journal contains a row this invoker did not write — the case where the
 * durable record is structurally different from the one the invoker produces.
 *
 * That matters because the restore path ends in an unchecked cast:
 *
 * ```
 * private fun restoredOutput(output: OperationOutput?) = when (val raw = output?.result) {
 *     null -> … Failed(REPLAY_COMPATIBILITY)
 *     else -> Success(EncodedStepValue((raw as JsonPrimitive).content))
 * }
 * ```
 *
 * `OperationOutput.result` is a `JsonElement`, so a `JsonObject` reaches that
 * cast. Every other malformed-durable-state case in this method is a TYPED
 * failure with a `FailureKind`; this one is an exception crossing the restore
 * path — the same defect class RP7-SEM-S3-R1 just corrected in the agent codec,
 * in a file nobody had looked at with hostile durable state.
 *
 * `CHARACTERIZED DEFECT` tests assert the observed behaviour, so the gap cannot
 * close silently; they go red the day it is fixed, at which point they are
 * rewritten to assert a typed `Failed`.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class S4A0ScriptedRestorePathCharacterizationTest {

    private object FixtureCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private class FixtureStep : StepDefinition<String, String> {
        val handlerInvocations = AtomicInteger(0)

        override val contract: StepContract<String, String> = StepContract(
            key = KEY,
            descriptor = StepDescriptor(
                stepId = KEY.value,
                name = "fixture",
                configRef = "",
                executionLocation = ExecutionLocation.AGENT,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
            ),
            inputCodec = FixtureCodec,
            outputCodec = FixtureCodec,
            requiredCapabilities = emptySet(),
        )

        override val handler: StepHandler<String, String> = StepHandler { input, _ ->
            handlerInvocations.incrementAndGet()
            (input.toIntOrNull() ?: throw IllegalStateException("fixture input must be numeric"))
                .plus(1).toString()
        }

        companion object {
            val KEY = PluginStepId("scripted.fixture.restore")
        }
    }

    private fun contextFor(call: ScriptedRegistryCall): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId(call.runId, 0, call.invocationOrdinal),
        runId = call.runId,
        stageName = "scripted",
        stageIndex = 0,
        stepIndex = call.invocationOrdinal,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = Files.createTempDirectory("s4a0-restore-"),
        eventSink = InMemoryEventStore(),
    )

    private fun newCall(): ScriptedRegistryCall = ScriptedRegistryCall(
        runId = "s4a0-run",
        entryPointId = "ep-1",
        callSiteId = ScriptedCallSiteId("src.kts:10:5:fixture"),
        dynamicScopePath = listOf("loop:src.kts:9:3[0]"),
        invocationOrdinal = 0,
        stepKey = FixtureStep.KEY,
        encodedInput = EncodedStepValue("41"),
        definitionDigest = "s4-test-artifact-v1",
    )

    private fun invokerOver(journal: InMemoryOperationJournal): ScriptedRegistryInvoker {
        val registry = InMemoryStepRegistry().also { it.register(FixtureStep()) }
        return ScriptedRegistryInvoker(
            registry = registry,
            journal = journal,
            clock = SystemClock(),
            runtimeContextFactory = ::contextFor,
        )
    }

    /**
     * Run one FRESH invocation so the invoker writes a row with its own
     * fingerprint, then REPLACE that row's payload with a foreign JSON shape and
     * the same id.
     *
     * The fingerprint is deliberately NOT hand-built: reproducing the invoker's
     * `OperationInput` here would duplicate the logic under test and drift from
     * it on the next change. Letting the invoker write it, then swapping only the
     * payload, models the real scenario — durable state written by a different
     * codec version, which is precisely what S4-C4 must fail closed on.
     */
    private fun journalWithForeignPayload(
        result: kotlinx.serialization.json.JsonElement,
    ): InMemoryOperationJournal {
        val journal = InMemoryOperationJournal(SystemClock())
        val call = newCall()
        val invoker = invokerOver(journal)
        val fresh = runBlocking { invoker.invoke(call) }
        assertTrue(fresh is ScriptedRegistryResult.Success, "the priming invocation must succeed, got $fresh")

        val written = journal.get(call.operationId())
        assertTrue(written != null, "the priming invocation must have persisted a row")
        journal.append(
            (written as dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation).copy(
                output = OperationOutput(result = result, durationMs = 1L, finishedAt = 1_700_000_000_000L),
                cachedOutput = OperationOutput(result = result, durationMs = 1L, finishedAt = 1_700_000_000_000L),
            ),
        )
        return journal
    }

    // ------------------------------------------------------------------
    // The control: a well-formed persisted primitive restores fine
    // ------------------------------------------------------------------

    @Test
    fun `a well-formed persisted primitive is restored as a typed value`() = runBlocking {
        val result = invokerOver(journalWithForeignPayload(JsonPrimitive("42")))
            .invoke(newCall())
        assertTrue(result is ScriptedRegistryResult.Success, "expected Success, got $result")
        assertEquals(42, (result as ScriptedRegistryResult.Success).encodedOutput.value.toInt())
    }

    // ------------------------------------------------------------------
    // CHARACTERIZED DEFECT — a non-primitive durable payload escapes as an exception
    // ------------------------------------------------------------------

    @Test
    fun `CHARACTERIZED DEFECT - a persisted JSON OBJECT escapes the restore path as an exception`() {
        val foreign = JsonObject(mapOf("value" to JsonPrimitive("42")))
        val outcome = runCatching {
            runBlocking { invokerOver(journalWithForeignPayload(foreign)).invoke(newCall()) }
        }

        assertTrue(
            outcome.isFailure,
            "MEASURED DEFECT: a durable payload that is not a JsonPrimitive reaches the " +
                "unchecked `raw as JsonPrimitive` cast in restoredOutput and escapes as " +
                "ClassCastException rather than becoming a ScriptedRegistryResult.Failed with a " +
                "FailureKind. Every OTHER malformed-durable-state case in this method is typed.",
        )
        assertTrue(
            outcome.exceptionOrNull() is ClassCastException,
            "MEASURED DEFECT: the escaping throwable is ${outcome.exceptionOrNull()?.let { it::class.simpleName }}, " +
                "which is exactly the untyped control flow this repository's typed-result law exists to remove. " +
                "Fails the day the cast is replaced with a typed failure (S4-B / S4-C4).",
        )
    }

    @Test
    fun `CHARACTERIZED DEFECT - the same gap exists for a persisted JSON ARRAY`() {
        val foreign = JsonObject(mapOf("value" to JsonPrimitive("42"))).jsonObject
        val foreignArray = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("42")))
        val outcome = runCatching {
            runBlocking { invokerOver(journalWithForeignPayload(foreignArray)).invoke(newCall()) }
        }
        assertTrue(
            outcome.isFailure && outcome.exceptionOrNull() is ClassCastException,
            "MEASURED DEFECT: same cause, different payload shape (array instead of object): ${outcome.exceptionOrNull()}",
        )
        assertTrue(foreign.values.isNotEmpty(), "control: the object payload really was a JsonObject")
    }
}
