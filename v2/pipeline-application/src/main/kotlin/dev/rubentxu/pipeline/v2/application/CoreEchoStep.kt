package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.echo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Typed input payload of `core.echo`. */
data class EchoInput(val text: String)

/**
 * `core.echo` registered as an open [StepDefinition] (B1.2b, ADR-0070).
 *
 * Registers through the SAME generic mechanism an external plugin would use. The handler reuses the
 * existing SDK echo runtime authority and emits [dev.rubentxu.pipeline.v2.events.EchoOutputCaptured]
 * via the [EventSink] it receives as a declared capability. It returns the echo payload (the content
 * line) as its typed result.
 */
object CoreEchoStep {

    val KEY: PluginStepId = PluginStepId("core.echo")

    /**
     * B1.2c3-slice1: echo's durable input codec emits the SAME canonical dsl-v1 envelope the compiler
     * produces for `core.echo` (`{"kind":"echo","text":...}`, byte-identical via the same kotlinx JSON
     * serializer). This makes the registry definition durable-spine eligible (a well-formed JSON-object
     * payload, per the CDE.3-e1/e2 boundary) AND keeps fingerprint/journal identity continuous across
     * the eventual echo migration (legacy-routed today and registry-routed after the flip share the
     * exact durable payload string). Round-trip `encode(I) -> payload.encoded -> decode` is lossless.
     */
    private val inputCodec = object : StepCodec<EchoInput> {
        override fun encode(value: EchoInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("kind", JsonPrimitive("echo"))
                    put("text", JsonPrimitive(value.text))
                }),
            )

        override fun decode(encoded: EncodedStepValue): EchoInput {
            val objectValue = Json.parseToJsonElement(encoded.value).jsonObject
            require(objectValue["kind"]?.jsonPrimitive?.content == "echo") {
                "echo payload kind must be 'echo'"
            }
            return EchoInput(objectValue.getValue("text").jsonPrimitive.content)
        }
    }

    private val outputCodec = object : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)

        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private val descriptor = StepDescriptor(
        stepId = "core.echo",
        name = "echo",
        configRef = "",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    val definition: StepDefinition<EchoInput, String> = object : StepDefinition<EchoInput, String> {
        override val contract: StepContract<EchoInput, String> = StepContract(
            key = KEY,
            descriptor = descriptor,
            inputCodec = inputCodec,
            outputCodec = outputCodec,
            requiredCapabilities = setOf(EVENT_SINK_CAPABILITY),
        )

        override val handler: StepHandler<EchoInput, String> = StepHandler { input, ctx ->
            val sink: EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)
            echo(StepContext(runId = ctx.runId.value), input.text, sink, ctx.stepIndex)
        }
    }

    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
