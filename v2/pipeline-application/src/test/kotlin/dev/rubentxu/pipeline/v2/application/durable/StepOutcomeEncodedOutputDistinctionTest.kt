package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * LB-02 / G3-A2 — preserve the StepOutcome / encoded-output distinction.
 *
 * **Goal**: prove that the registry execution boundary NEVER conflates the
 * two responsibilities (classification + typed result) into a single
 * String. Each responsibility crosses the boundary on its own channel:
 *
 *  - `outcome: StepOutcome` — closed algebra (Success / Failure / ...).
 *  - `encodedOutput: EncodedStepValue?` — typed `O` under the Step's
 *    declared output codec.
 *
 * The user's rule is explicit:
 *
 * > "Para sh: stdout/stderr/exit/interruption pueden participar en la
 * >  clasificación y a la vez formar parte del output consumible.
 * >  No hagas que un único String pierda esta información."
 *
 * The carrier is a `data class(outcome, encodedOutput)` already; this test
 * class locks that distinction with three focused laws:
 *
 *  1. A structured typed output (multi-field JSON object) crosses the
 *     boundary under `encodedOutput` WITHOUT losing its structure to a String.
 *  2. The encoded form is exactly the outputCodec's product — reversible.
 *  3. A non-Success StepOutcome and a non-null `encodedOutput` are two
 *     orthogonal axes: the boundary's classifier does not erase the typed
 *     result for any failure mode (thrown = null; explicit classifier-rich
 *     failure = can still carry the encoded partial output, by future WU
 *     extension; today's slice collapses to null on thrown with a witness).
 *
 * @see docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md
 */
@Timeout(15)
class StepOutcomeEncodedOutputDistinctionTest {

    /** A typed-output fixture that mirrors sh's closed result shape but is generic. */
    private data class RichOutput(
        val kind: String,             // "STDOUT" | "FAILED" | "UNIT" | ...
        val stdout: String? = null,
        val exitCode: Int? = null,
        val message: String? = null,
        val failureKind: String? = null,
        val durationMs: Long? = null,
    )

    private data class RichInput(val text: String)

    private val richKey = PluginStepId("test.rich-fixture")

    /**
     * Output codec explicitly mirrors the sh contract shape from the
     * path-A contract draft (`docs/v2/00-context/LB02_CORE_SH_CONTRACT_DRAFT.md`):
     * the discriminant `kind` plus per-variant fields carry the closed
     * result WITHOUT flattening to a String.
     */
    private val richOutputCodec = object : StepCodec<RichOutput> {
        override fun encode(value: RichOutput): EncodedStepValue = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", JsonPrimitive(value.kind))
                value.stdout?.let { put("stdout", JsonPrimitive(it)) }
                value.exitCode?.let { put("exitCode", JsonPrimitive(it)) }
                value.message?.let { put("message", JsonPrimitive(it)) }
                value.failureKind?.let { put("failureKind", JsonPrimitive(it)) }
                value.durationMs?.let { put("durationMs", JsonPrimitive(it)) }
            }),
        )

        override fun decode(encoded: EncodedStepValue): RichOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            return RichOutput(
                kind = obj.getValue("kind").jsonPrimitive.content,
                stdout = obj["stdout"]?.jsonPrimitive?.content,
                exitCode = obj["exitCode"]?.jsonPrimitive?.content?.toIntOrNull(),
                message = obj["message"]?.jsonPrimitive?.content,
                failureKind = obj["failureKind"]?.jsonPrimitive?.content,
                durationMs = obj["durationMs"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
    }

    private val richInputCodec = object : StepCodec<RichInput> {
        override fun encode(value: RichInput) = EncodedStepValue(
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("kind", JsonPrimitive("rich-fixture"))
                put("text", JsonPrimitive(value.text))
            }),
        )

        override fun decode(encoded: EncodedStepValue): RichInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "rich-fixture")
            return RichInput(obj.getValue("text").jsonPrimitive.content)
        }
    }

    /** A handler that returns a rich Stdout-like typed result preserving the discriminated shape. */
    private val richHandler = StepHandler<RichInput, RichOutput> { input, _ ->
        RichOutput(
            kind = "STDOUT",
            stdout = "${input.text}\n",
            exitCode = 0,
            durationMs = 0L,
        )
    }

    private val richStep: StepDefinition<RichInput, RichOutput> = object : StepDefinition<RichInput, RichOutput> {
        override val contract: StepContract<RichInput, RichOutput> = StepContract(
            key = richKey,
            descriptor = StepDescriptor(
                stepId = "test.rich-fixture",
                name = "rich-fixture",
                configRef = "",
                executionLocation = ExecutionLocation.AGENT,
                effects = listOf(Effect.EXECUTES_SUBPROCESS),
                replayPolicy = ReplayPolicy.RERUN,
            ),
            inputCodec = richInputCodec,
            outputCodec = richOutputCodec,
            requiredCapabilities = emptySet(),
        )
        override val handler: StepHandler<RichInput, RichOutput> = richHandler
    }

    private fun buildContext(): CanonicalRuntimeContext = CanonicalRuntimeContext(
        opId = OpId("g3-a2-rich", 0, 0),
        runId = "g3-a2-rich",
        stageName = "fixture",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions.EMPTY,
        controlDirRoot = Files.createTempDirectory("g3-a2-"),
        eventSink = InMemoryEventStore(),
    )

    private fun registryWith(vararg defs: StepDefinition<*, *>): InMemoryStepRegistry =
        InMemoryStepRegistry().apply { defs.forEach { register(it) } }

    // -------- Law 1: structured typed output crosses under encodedOutput --------

    @Test
    fun `typed output with multiple fields crosses as EncodedStepValue with all fields preserved`() = runBlocking {
        val registry = registryWith(richStep)
        val ctx = buildContext()
        val prepared = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = richKey,
            encodedInput = richInputCodec.encode(RichInput("echo rich")),
            availableCapabilities = emptySet(),
        ).let {
            require(it is ExecutionPreparation.Ready) { "admission: $it" }
            (it as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution
        }
        val result = RegistryExecutionBoundary.coexecute(prepared, ctx)
        assertEquals(StepOutcome.Success, result.outcome, "rich handler returns Success")
        assertNotNull(result.encodedOutput, "rich typed result MUST cross under encodedOutput, not be erased")

        // The encoded payload must retain the structured fields — kind, stdout, exitCode, durationMs.
        val obj = Json.parseToJsonElement(result.encodedOutput!!.value).jsonObject
        assertEquals("STDOUT", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("echo rich\n", obj["stdout"]?.jsonPrimitive?.content)
        assertEquals("0", obj["exitCode"]?.jsonPrimitive?.content)
        assertNotNull(obj["durationMs"], "durationMs must survive the carrier intact")
        // Sanity: NOT flattened to a single String. If it had been, the JSON parse would have
        // returned a JsonPrimitive, not a JsonObject.
        assertTrue(parsedIsObject(result.encodedOutput!!.value), "encodedOutput must be a JSON object, not a String")
    }

    // -------- Law 2: encoded output is reversible via the declared outputCodec --------

    @Test
    fun `encoded output decodes back to the original typed result via the step outputCodec`() = runBlocking {
        val registry = registryWith(richStep)
        val ctx = buildContext()
        val prepared = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = richKey,
            encodedInput = richInputCodec.encode(RichInput("decode round-trip")),
            availableCapabilities = emptySet(),
        ).let {
            require(it is ExecutionPreparation.Ready) { "admission: $it" }
            (it as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution
        }
        val result = RegistryExecutionBoundary.coexecute(prepared, ctx)

        // Decode through the SAME codec the boundary used (proves reversibility
        // and rule "decode never happens at the durable coordinator").
        val decoded: RichOutput = richOutputCodec.decode(result.encodedOutput!!)
        assertEquals(RichOutput(kind = "STDOUT", stdout = "decode round-trip\n", exitCode = 0, durationMs = 0L), decoded)
    }

    // -------- Law 3: classifier failure and typed-output are orthogonal axes --------

    @Test
    fun `thrown handler — encodedOutput is null but the carrier still distinguishes Failure kind`() = runBlocking {
        val throwingHandler = StepHandler<RichInput, RichOutput> { _, _ ->
            throw IllegalStateException("rich fixture throw for distinction test")
        }
        val throwingStep = object : StepDefinition<RichInput, RichOutput> {
            override val contract: StepContract<RichInput, RichOutput> = StepContract(
                key = richKey,
                descriptor = StepDescriptor(
                    stepId = "test.rich-fixture",
                    name = "rich-fixture",
                    configRef = "",
                    executionLocation = ExecutionLocation.AGENT,
                    effects = listOf(Effect.EXECUTES_SUBPROCESS),
                    replayPolicy = ReplayPolicy.RERUN,
                ),
                inputCodec = richInputCodec,
                outputCodec = richOutputCodec,
                requiredCapabilities = emptySet(),
            )
            override val handler: StepHandler<RichInput, RichOutput> = throwingHandler
        }
        val registry = registryWith(throwingStep)
        val ctx = buildContext()
        val prepared = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = richKey,
            encodedInput = richInputCodec.encode(RichInput("thrown-rich")),
            availableCapabilities = emptySet(),
        ).let {
            require(it is ExecutionPreparation.Ready) { "admission: $it" }
            (it as ExecutionPreparation.Ready).prepared as PreparedRegistryExecution
        }
        val result = RegistryExecutionBoundary.coexecute(prepared, ctx)

        // Outcome IS typed — Failure(ENGINE), distinct from any other shape.
        val failure = result.outcome as StepOutcome.Failure
        assertEquals(FailureKind.ENGINE, failure.failure.kind, "today's boundary classifies thrown as ENGINE")
        assertEquals(null, result.encodedOutput, "thrown handler MUST NOT carry a typed output (no successful terminal to encode)")

        // The two axes are observed independently:
        //  - StepOutcome.Failure is real, with kind
        //  - encodedOutput is null
        // This is the "orthogonal axes" law: collapsing to a String would have lost both.
        assertNotEquals(StepOutcome.Success, failure)
        assertNull(result.encodedOutput)
    }

    @Test
    fun `carrier — outcome and encodedOutput are read independently by structured-property access`() {
        // Structural witness: the carrier exposes `outcome` and `encodedOutput`
        // as separate properties; reading one MUST NOT require inspecting the
        // other. A reader that needed to JSONParse the encoded output to learn
        // the StepOutcome (or vice versa) would conflate the two responsibilities.
        val outcome: StepOutcome = StepOutcome.Failure(
            PipelineFailure(FailureKind.SCRIPT, "structured failure"),
        )
        val encoded: EncodedStepValue? = EncodedStepValue("""{"kind":"FAILED","message":"x"}""")
        val carrier = CommonExecutionResult(outcome = outcome, encodedOutput = encoded)

        // Each responsibility is read through its own property. The OTHER property is opaque at this site.
        val observedOutcome: StepOutcome = carrier.outcome
        val observedEncoded: EncodedStepValue? = carrier.encodedOutput

        assertEquals(outcome, observedOutcome)
        assertEquals(encoded, observedEncoded)
        // Round-trip independent: read `outcome` -> String stays the SCRIPT message; read `encodedOutput` -> String stays the JSON object.
        assertTrue(observedOutcome is StepOutcome.Failure)
        assertTrue(observedEncoded!!.value.startsWith("{"))
    }

    // -------- Helper --------

    private fun parsedIsObject(s: String): Boolean =
        runCatching { Json.parseToJsonElement(s) is JsonObject }.getOrDefault(false)
}
