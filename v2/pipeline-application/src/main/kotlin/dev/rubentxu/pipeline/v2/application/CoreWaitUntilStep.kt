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
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Typed input payload of `core.waitUntil`.
 *
 * The legacy wire payload for `waitUntil(initialRecurrencePeriod = X, quiet = Y)` is:
 * `{"kind":"waitUntil","initialRecurrencePeriod":X,"quiet":Y}`
 *
 * The codec preserves the legacy envelope structure.
 */
data class WaitUntilInput(
    val initialRecurrencePeriod: Long = 1000L,
    val quiet: Boolean = false,
) {
    init {
        require(initialRecurrencePeriod >= 0) {
            "core.waitUntil requires initialRecurrencePeriod >= 0"
        }
    }
}

/**
 * Typed output for `core.waitUntil`.
 *
 * The handler emits WaitUntilPolled and WaitUntilCompleted events as the observable
 * runtime output. The typed output carries the final outcome for scriptability.
 */
data class WaitUntilOutput(
    val resultOutcome: String, // "completed" or "deadline-exceeded"
    val totalAttempts: Int,
    val totalDurationMs: Long,
) : TypedStepOutput {
    override val outcome: StepOutcome
        get() = if (resultOutcome == "completed") StepOutcome.Success else StepOutcome.Failure(
            dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                kind = dev.rubentxu.pipeline.v2.domain.FailureKind.TIMEOUT,
                message = "waitUntil deadline exceeded after $totalAttempts attempts and ${totalDurationMs}ms"
            )
        )
}

/**
 * Registry candidate for `core.waitUntil`.
 *
 * G1 registers this candidate WITHOUT changing `LEGACY_PLUGIN_IDS`, the legacy decoder,
 * the metadata row, or the legacy dispatcher. StructuralFamilyResolver therefore
 * continues to route production invocations to LegacyCore until the later cutover gate.
 *
 * waitUntil is a Block Step with a condition body. The registry candidate emits the
 * typed events (WaitUntilPolled / WaitUntilCompleted) but the actual condition
 * evaluation requires the BodyInvoker mechanism (ADR-0073). This G1 candidate
 * follows the stub pattern from the legacy dispatcher.
 *
 * Capability design:
 * - [EVENT_SINK_CAPABILITY] publishes the durable observation events.
 */
object CoreWaitUntilStep {

    val KEY: PluginStepId = PluginStepId("core.waitUntil")

    private const val MAX_BACKOFF_MS = 60_000L
    private const val DEADLINE_MS = Long.MAX_VALUE

    private val inputCodec = object : StepCodec<WaitUntilInput> {
        override fun encode(value: WaitUntilInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("waitUntil"))
                        put("initialRecurrencePeriod", JsonPrimitive(value.initialRecurrencePeriod))
                        put("quiet", JsonPrimitive(value.quiet))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): WaitUntilInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "waitUntil") {
                "core.waitUntil payload kind must be 'waitUntil'"
            }
            val initialRecurrencePeriod = obj["initialRecurrencePeriod"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1000L
            val quiet = obj["quiet"]?.jsonPrimitive?.booleanOrNull ?: false
            return WaitUntilInput(initialRecurrencePeriod, quiet)
        }
    }

    private val outputCodec = object : StepCodec<WaitUntilOutput> {
        override fun encode(value: WaitUntilOutput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("waitUntil"))
                        put("outcome", JsonPrimitive(value.resultOutcome))
                        put("totalAttempts", JsonPrimitive(value.totalAttempts))
                        put("totalDurationMs", JsonPrimitive(value.totalDurationMs))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): WaitUntilOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "waitUntil") {
                "core.waitUntil output payload kind must be 'waitUntil'"
            }
            return WaitUntilOutput(
                resultOutcome = obj.getValue("outcome").jsonPrimitive.content,
                totalAttempts = obj.getValue("totalAttempts").jsonPrimitive.content.toInt(),
                totalDurationMs = obj.getValue("totalDurationMs").jsonPrimitive.content.toLong(),
            )
        }
    }

    // Inherit from legacy metadata: READ_ONLY + MEMOIZED
    private val descriptor = StepDescriptor(
        stepId = "core.waitUntil",
        name = "waitUntil",
        configRef = "",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val capabilityRoutedHandler: StepHandler<WaitUntilInput, WaitUntilOutput> =
        StepHandler { input, ctx ->
            val sink: EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)
            val startTime = System.currentTimeMillis()

            // Emit one WaitUntilPolled event (stub pattern: condition assumed true)
            sink.append(
                WaitUntilPolled(
                    eventId = UUID.randomUUID().toString(),
                    runId = ctx.runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    attempt = 1,
                    durationMs = 0L,
                    conditionResult = true, // Stub: condition assumed met
                ),
            )

            // Emit WaitUntilCompleted with "completed" outcome
            sink.append(
                WaitUntilCompleted(
                    eventId = UUID.randomUUID().toString(),
                    runId = ctx.runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    totalAttempts = 1,
                    totalDurationMs = 0L,
                    outcome = "completed",
                ),
            )

            WaitUntilOutput(
                resultOutcome = "completed",
                totalAttempts = 1,
                totalDurationMs = 0L,
            )
        }

    val definition: StepDefinition<WaitUntilInput, WaitUntilOutput> =
        object : StepDefinition<WaitUntilInput, WaitUntilOutput> {
            override val contract: StepContract<WaitUntilInput, WaitUntilOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(EVENT_SINK_CAPABILITY) as Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability>,
            )

            override val handler: StepHandler<WaitUntilInput, WaitUntilOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
