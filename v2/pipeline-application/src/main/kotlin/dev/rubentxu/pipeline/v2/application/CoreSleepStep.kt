package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * Typed input payload of `core.sleep`.
 *
 * The invariant is validated while decoding the complete canonical envelope, before the handler
 * can create an effect. A non-negative `Long` is represented as a [kotlin.time.Duration] directly,
 * avoiding the legacy `seconds * 1000L` overflow path.
 */
data class CoreSleepInput(val seconds: Long) {
    init {
        require(seconds >= 0) { "core.sleep requires seconds >= 0" }
    }
}

/**
 * Typed successful output for `core.sleep`.
 *
 * Cancellation is an execution mechanism, not a handler-produced terminal result. The generic
 * execution boundary preserves ordinary [kotlinx.coroutines.CancellationException] propagation;
 * timeout classification is likewise owned by that generic boundary, never by this Step key.
 */
data object CoreSleepOutput : TypedStepOutput {
    override val outcome: StepOutcome = StepOutcome.Success
}

/**
 * Candidate registry authority for `core.sleep`.
 *
 * G1 registers this definition but does not change `LEGACY_PLUGIN_IDS`, the legacy decoder, the
 * metadata row, or the legacy dispatcher. StructuralFamilyResolver therefore continues to route
 * production invocations to LegacyCore until the later cutover gate.
 *
 * A temporal operation does not imply a temporal capability: this handler requires only the
 * coroutine context supplied by the execution boundary and declares no runtime capability.
 */
object CoreSleepStep {
    val KEY: PluginStepId = PluginStepId("core.sleep")

    private val inputCodec = object : StepCodec<CoreSleepInput> {
        override fun encode(value: CoreSleepInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("kind", JsonPrimitive("sleep"))
                    put("seconds", JsonPrimitive(value.seconds))
                }),
            )

        override fun decode(encoded: EncodedStepValue): CoreSleepInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "sleep") {
                "core.sleep payload kind must be 'sleep'"
            }
            return CoreSleepInput(obj.getValue("seconds").jsonPrimitive.content.toLong())
        }
    }

    private val outputCodec = object : StepCodec<CoreSleepOutput> {
        override fun encode(value: CoreSleepOutput): EncodedStepValue =
            EncodedStepValue("{\"kind\":\"sleep\",\"outcome\":\"SUCCESS\"}")

        override fun decode(encoded: EncodedStepValue): CoreSleepOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "sleep") {
                "core.sleep output payload kind must be 'sleep'"
            }
            require(obj["outcome"]?.jsonPrimitive?.content == "SUCCESS") {
                "core.sleep output outcome must be 'SUCCESS'"
            }
            return CoreSleepOutput
        }
    }

    private val descriptor = StepDescriptor(
        stepId = "core.sleep",
        name = "sleep",
        configRef = "",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    val definition: StepDefinition<CoreSleepInput, CoreSleepOutput> = object :
        StepDefinition<CoreSleepInput, CoreSleepOutput> {
        override val contract: StepContract<CoreSleepInput, CoreSleepOutput> = StepContract(
            key = KEY,
            descriptor = descriptor,
            inputCodec = inputCodec,
            outputCodec = outputCodec,
            requiredCapabilities = emptySet(),
        )

        override val handler: StepHandler<CoreSleepInput, CoreSleepOutput> =
            StepHandler { input, _ ->
                // Duration construction is overflow-safe. `delay` is cancellable and suspends
                // instead of blocking a worker thread. No blocking wait and no temporal capability.
                delay(input.seconds.seconds)
                CoreSleepOutput
            }
    }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
