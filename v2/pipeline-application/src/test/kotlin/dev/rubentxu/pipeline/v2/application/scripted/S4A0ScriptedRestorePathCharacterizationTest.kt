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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
        return dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture.build(
            registry = registry,
            journal = journal,
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
        val foreign = OperationOutput(result = result, durationMs = 1L, finishedAt = 1_700_000_000_000L)
        // Swap ONLY the payload, preserving whatever row type the writer produced. This test
        // used to hard-cast to `MemoizedOperation`, which was true while the scripted invoker
        // wrote its own rows. Under ADR-0103 RPL-4 the canonical `DurableStepExecutor` writes
        // the row, and it writes a `RerunOperation` — so the cast became a `ClassCastException`
        // thrown out of `invoke`, which is the very failure mode S4-C4 exists to prevent.
        // The subject of this test is the foreign PAYLOAD, never the row class.
        journal.append(
            when (written) {
                is dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation ->
                    written.copy(output = foreign, cachedOutput = foreign)
                is dev.rubentxu.pipeline.v2.domain.durable.RerunOperation -> written.copy(output = foreign)
                else -> error("unexpected durable row type: ${written!!::class.simpleName}")
            },
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
    // S4-C4 — the cast is gone; every unreadable durable payload is typed
    // ------------------------------------------------------------------

    /**
     * The three shapes the old `raw as JsonPrimitive` cast could not survive, plus
     * the one it could survive *wrongly*.
     *
     * `JsonNull` is the case the S4-A0 characterization never exercised and the
     * original code would have accepted: it IS a `JsonPrimitive`, so the cast
     * succeeds, and `content` is the literal string `"null"`. A step that persisted
     * nothing would therefore have restored as a caller-visible four-character
     * value — a fabricated one, which is the thing
     * [ScriptedRegistryResult] exists to prevent.
     *
     * Parameterised over the closed `JsonElement` shape set so a shape added to the
     * hierarchy later has no path to a fabricated value: the production `when` is
     * exhaustive over the same ADT, and this test enumerates it independently.
     */
    @ParameterizedTest(name = "a persisted {0} becomes a typed REPLAY_COMPATIBILITY failure")
    @MethodSource("unreadableDurablePayloads")
    fun `S4-C4 - an unreadable durable payload is a typed failure, never an exception or a fabricated value`(
        shape: String,
        payload: JsonElement,
    ) {
        val outcome = runCatching {
            runBlocking { invokerOver(journalWithForeignPayload(payload)).invoke(newCall()) }
        }

        assertTrue(
            outcome.isSuccess,
            "S4-C4: a persisted $shape must not throw out of invoke. Got ${outcome.exceptionOrNull()}. " +
                "An exception here is the defect this slice closed.",
        )
        val result = outcome.getOrThrow()
        assertTrue(
            result is ScriptedRegistryResult.Failed,
            "S4-C4: a persisted $shape must be a typed Failed, not a value. Got $result. " +
                "Returning a value the Step never produced is a fabricated value.",
        )
        val failure = (result as ScriptedRegistryResult.Failed).failure
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
            failure.kind,
            "S4-C4: a durable payload this runtime cannot read IS a replay-compatibility failure",
        )
        assertTrue(
            shape in failure.message,
            "S4-C4: the failure must name the shape it rejected, so an operator reading " +
                "a real journal can tell a codec mismatch from a missing output. Got: ${failure.message}",
        )
    }

    companion object {
        @JvmStatic
        fun unreadableDurablePayloads(): List<Arguments> = listOf(
            Arguments.of("JSON object", JsonObject(mapOf("value" to JsonPrimitive("42")))),
            Arguments.of("JSON array", JsonArray(listOf(JsonPrimitive("42")))),
            Arguments.of("JSON null", JsonNull),
        )
    }
}
