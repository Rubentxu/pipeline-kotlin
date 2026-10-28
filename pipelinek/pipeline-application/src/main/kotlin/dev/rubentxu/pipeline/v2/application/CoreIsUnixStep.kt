package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.UnixDetected
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * Typed input payload of `core.isUnix`. The legacy wire payload for this
 * Step is the empty object `"{}"` (compiler `encodePayload` else-branch), so the input
 * is a unit value and the codec preserves that exact durable envelope.
*/
data object IsUnixInput

/**
 * TYPED_RUNTIME_OUTPUT — APPROVED_ARCHITECTURAL_DELTA (/, decision)
 * Legacy durable path produces NO typed output (`StepOutcome.Success` only; the
 * boolean is observable solely through the `UnixDetected` event). The candidate
 * deliberately exposes the classified value as a typed result because the product
 * baseline requires `isUnix` to be a runtime-valued Step (the eventual reconnection
 * of `PipelineDsl.isUnix` to a real execution-target observation depends on it).
 * Durable law: fresh/rerun OBSERVE the environment; resume/reuse REPRODUCE the
 * persisted observation (`IsUnixOutput` + original `UnixDetected`) without
 * re-executing the handler or re-observing `PlatformIdentity`. A stale observation
 * after an OS change is correct durable semantics, not a bug.
*/
data class IsUnixOutput(
    val isUnix: Boolean,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Success
}

/**
 * Registry candidate for `core.isUnix` (/ registry seam proof)
 * Registers this candidate WITHOUT changing `LEGACY_PLUGIN_IDS`, the legacy decoder
 * the metadata row, or the legacy dispatcher: `StructuralFamilyResolver`'s
 * legacy-membership-wins rule keeps LegacyCore as the production authority until the
 * / flip. Counters stay 8 / 8 / 8
 * Classification policy is **PATH_B verbatim**:
 * `osName.lowercase()` contains any of
 * `linux | mac | darwin | freebsd`. The DSL-side whitelist (PATH_A) is deliberately NOT
 * Copied; the canonical-policy decision belongs to with a clean `C == B` baseline
 * Capability separation:
 * - [PLATFORM_IDENTITY_CAPABILITY] owns the environmental OBSERVATION (`osName`);
 *   the single `System.getProperty("os.name")` read lives in the capability bridge,
 *   never in the handler.
 * - The Step owns the isUnix classification POLICY.
 * - [EVENT_SINK_CAPABILITY] publishes the durable `UnixDetected` observation.
 * Adjective checks: input codec preserves the legacy `"{}"` envelope; the handler is
 * total (no exceptions as semantics); `UnixDetected` fields mirror the legacy dispatcher
 * byte-for-byte (uuid eventId, sequence 0L, sha256 hex of osName).
*/
object CoreIsUnixStep {

    val KEY: PluginStepId = PluginStepId("core.isUnix")

    /**
     * Classification now routes through the single canonical pure
     * Classifier [UnixPlatformClassifier.classifyUnix] (decision). The
     * PATH_B-verbatim body is retired; see the differential-freeze receipt for the
     * APPROVED_FIX / APPROVED_CONTRACT_DELTA matrix.
*/
    internal fun classify(osName: String): Boolean = UnixPlatformClassifier.classifyUnix(osName)

    private val inputCodec = object : StepCodec<IsUnixInput> {
        override fun encode(value: IsUnixInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), buildJsonObject {}),
            )

        override fun decode(encoded: EncodedStepValue): IsUnixInput {
            // Envelope structure only: legacy durable payload is an empty JSON object.
            Json.parseToJsonElement(encoded.value).jsonObject
            return IsUnixInput
        }
    }

    private val outputCodec = object : StepCodec<IsUnixOutput> {
        override fun encode(value: IsUnixOutput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("isUnix"))
                        put("isUnix", JsonPrimitive(value.isUnix))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): IsUnixOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "isUnix") {
                "core.isUnix output payload kind must be 'isUnix'"
            }
            return IsUnixOutput(obj.getValue("isUnix").jsonPrimitive.content.toBooleanStrict())
        }
    }

    // Byte-equivalent to the legacy CanonicalCoreStepMetadata["core.isUnix"] row.
    private val descriptor = StepDescriptor(
        stepId = "core.isUnix",
        name = "isUnix",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val capabilityRoutedHandler: StepHandler<IsUnixInput, IsUnixOutput> =
        StepHandler { _, ctx ->
            val platform: PlatformIdentity = ctx.capabilities.get(PLATFORM_IDENTITY_CAPABILITY)
            val sink: EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)
            val isUnix = classify(platform.osName)
            sink.append(
                UnixDetected(
                    eventId = UUID.randomUUID().toString(),
                    runId = ctx.runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    isUnix = isUnix,
                    osName = platform.osName,
                    sha256 = sha256(platform.osName),
                ),
            )
            IsUnixOutput(isUnix)
        }

    /**
     * The `core.isUnix` platform-probe digest, emitted in the Step's observability event.
     * Routed through [Sha256]. Byte-identical to the previous `input.toByteArray()`
     * On any toolchain; what changes is that the charset is stated rather than
     * inherited from the platform default.
*/
    internal fun sha256(input: String): String = Sha256.ofText(input)

    val definition: StepDefinition<IsUnixInput, IsUnixOutput> =
        object : StepDefinition<IsUnixInput, IsUnixOutput> {
            override val contract: StepContract<IsUnixInput, IsUnixOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(
                    PLATFORM_IDENTITY_CAPABILITY,
                    EVENT_SINK_CAPABILITY,
                ) as Set<StepCapability>,
            )

            override val handler: StepHandler<IsUnixInput, IsUnixOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(builder: StepRegistryBuilder) {
        builder.add(definition)
    }
}
