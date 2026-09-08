package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.application.CanonicalStructuralPreparation
import dev.rubentxu.pipeline.v2.application.StructuralPreparation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * CDE.3-e2: freezes the durable registry INPUT envelope boundary resolved in CDE.3-e1.
 *
 * Per the mini-ADR (`CDE_3e_REGISTRY_INPUT_ENVELOPE.md`), the durable registry input IS the registry
 * `StepCodec<I>.encode(I).value`, stored verbatim as `StepNode.payload.encoded` under `dsl-v1`, and
 * durable-spine eligibility REQUIRES that `EncodedStepValue` to be a well-formed JSON object. There is
 * no separate `RegistryDurableInput` type: the structural gate (which demands a JSON-object payload for
 * every spine step) is exactly the eligibility check, and typed decode happens ONLY inside registry
 * prepare on Execute.
 *
 * These tests freeze the boundary with NO production change: a JSON-object codec round-trips losslessly
 * through gate -> prepare (typed decode runs only at prepare), a raw-text payload is rejected as SCHEMA
 * by the gate before any decode, and identical payload strings yield identical fingerprints.
 */
@Timeout(10)
class RegistryDurableInputEnvelopeTest {

    private data class PingInput(val text: String)

    private val PING_KEY = PluginStepId("plugin.ping")

    private var handlerCalls = 0

    /** The plugin's input codec; produces a well-formed JSON OBJECT (the durable-spine shape). */
    private val inputCodec = object : StepCodec<PingInput> {
        override fun encode(value: PingInput): EncodedStepValue =
            EncodedStepValue(JsonObject(mapOf("text" to JsonPrimitive(value.text))).toString())

        override fun decode(encoded: EncodedStepValue): PingInput {
            val objectValue = Json.parseToJsonElement(encoded.value).jsonObject
            return PingInput(objectValue.getValue("text").jsonPrimitive.content)
        }
    }

    /** A registered step whose input codec emits a well-formed JSON OBJECT. */
    private fun jsonObjectRegistry(): InMemoryStepRegistry {
        val definition: StepDefinition<PingInput, String> = object : StepDefinition<PingInput, String> {
            override val contract: StepContract<PingInput, String> = StepContract(
                key = PING_KEY,
                descriptor = StepDescriptor(
                    stepId = "plugin.ping",
                    name = "ping",
                    configRef = "",
                    executionLocation = ExecutionLocation.CONTROLLER,
                    effects = listOf(Effect.READ_ONLY),
                    replayPolicy = ReplayPolicy.MEMOIZED,
                ),
                inputCodec = inputCodec,
                outputCodec = object : StepCodec<String> {
                    override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
                    override fun decode(encoded: EncodedStepValue): String = encoded.value
                },
                requiredCapabilities = emptySet(),
            )
            override val handler: StepHandler<PingInput, String> = StepHandler { input, _ ->
                handlerCalls++
                "ran:${input.text}"
            }
        }
        val registry = InMemoryStepRegistry()
        registry.register(definition)
        return registry
    }

    /** Spine StepNode whose durable payload is the codec-authored EncodedStepValue (a JSON object). */
    private fun spineNode(encoded: String) = OpaqueStepNode(
        id = StepId("stage-0-step-0"),
        pluginStepId = PING_KEY,
        payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = encoded),
    )

    @Test
    fun `json-object registry input round-trips losslessly through gate and prepare`() {
        val registry = jsonObjectRegistry()
        handlerCalls = 0

        // The plugin authors its durable payload with encode(I). The spine stores it verbatim.
        val payloadString = inputCodec.encode(PingInput(text = "hi")).value

        // Structural gate passes a well-formed dsl-v1 JSON object and projects it verbatim.
        val gate = CanonicalStructuralPreparation.prepare(spineNode(payloadString))
        assertTrue(gate is StructuralPreparation.Ready, "a JSON-object payload must pass the spine gate")
        assertEquals(
            payloadString,
            (gate as StructuralPreparation.Ready).invocation.encodedInput.value,
            "gate must carry payload verbatim",
        )

        // Registry prepare decodes the SAME verbatim payload losslessly; handler never runs.
        val prep = RegistryExecutionPreparation.prepare(
            registry,
            PING_KEY,
            (gate as StructuralPreparation.Ready).invocation.encodedInput,
            emptySet(),
        )
        assertTrue(prep is ExecutionPreparation.Ready, "a valid JSON-object input must prepare Ready")
        val prepared = (prep as ExecutionPreparation.Ready).prepared
        assertTrue(prepared is PreparedRegistryExecution, "registry prepare carries a PreparedRegistryExecution")
        assertEquals(
            PingInput("hi"),
            (prepared as PreparedRegistryExecution).decodedInput,
            "decode must be lossless over the verbatim payload",
        )
        assertEquals(0, handlerCalls, "prepare must decode WITHOUT running the handler")
    }

    @Test
    fun `raw-text registry input is rejected as SCHEMA by the gate before any decode`() {
        val registry = jsonObjectRegistry()
        handlerCalls = 0

        // A raw-text EncodedStepValue (e.g. CoreEchoStep's `"hi"`) is NOT a JSON object: it is
        // durable-spine-INELIGIBLE regardless of whether the key is registered, because the spine gate
        // demands a JSON-object payload for every step. decode is never reached on that path.
        val gate = CanonicalStructuralPreparation.prepare(spineNode("hi"))
        assertTrue(gate is StructuralPreparation.Rejected, "a non-JSON payload must be rejected by the spine gate")

        // Even a registered JSON-object codec cannot recover a raw payload at prepare: decode fails
        // and rejects without running the handler.
        val prep = RegistryExecutionPreparation.prepare(registry, PING_KEY, EncodedStepValue("hi"), emptySet())
        assertTrue(prep is ExecutionPreparation.Rejected, "raw text cannot be decoded by a JSON-object codec")
        assertEquals(0, handlerCalls)
    }

    @Test
    fun `identical payload string yields identical fingerprint over the durable OperationInput`() {
        val payloadString = inputCodec.encode(PingInput(text = "same")).value
        val operationInput = OperationInput(
            stepId = PING_KEY.value,
            params = mapOf("payload" to JsonPrimitive(payloadString)),
            runId = "run-a",
            attempt = 1,
        )
        val first = Fingerprint.compute(operationInput, PING_KEY.value, ReplayPolicy.MEMOIZED, attempt = 1)
        val second = Fingerprint.compute(operationInput, PING_KEY.value, ReplayPolicy.MEMOIZED, attempt = 1)
        assertEquals(first.hex, second.hex, "same durable payload must produce a deterministic fingerprint")
        assertNotNull(first.hex)
    }
}
