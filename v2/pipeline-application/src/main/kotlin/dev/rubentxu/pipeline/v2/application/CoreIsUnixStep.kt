package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.UnixDetected
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Typed input payload of `core.isUnix` (S2-A5 / G1). The legacy wire payload for this
 * Step is the empty object `"{}"` (compiler `encodePayload` else-branch), so the input
 * is a unit value and the codec preserves that exact durable envelope.
 */
data object IsUnixInput

/**
 * TYPED_RUNTIME_OUTPUT — CANDIDATE_ARCHITECTURAL_DELTA — **NOT YET APPROVED** (S2-A5 / G1).
 *
 * The legacy durable path produces NO typed output (`StepOutcome.Success` only; the
 * boolean is observable solely through the `UnixDetected` event). The candidate
 * deliberately exposes the classified value as a typed result because the product
 * baseline requires `isUnix` to be a runtime-valued Step (the eventual reconnection
 * of `PipelineDsl.isUnix` to a real execution-target observation depends on it).
 * Whether this output is persisted as `OperationOutput`, how it participates in
 * replay, and how it feeds the DSL are G2 decisions. G1 only makes the delta visible.
 */
data class IsUnixOutput(
    val isUnix: Boolean,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Success
}

/**
 * Registry candidate for `core.isUnix` (S2-A5 / G1 registry seam proof).
 *
 * G1 registers this candidate WITHOUT changing `LEGACY_PLUGIN_IDS`, the legacy decoder,
 * the metadata row, or the legacy dispatcher: `StructuralFamilyResolver`'s
 * legacy-membership-wins rule keeps LegacyCore as the production authority until the
 * G3/G4 flip. Counters stay 8 / 8 / 8.
 *
 * Classification policy is **PATH_B verbatim** (G0 characterization, receipt
 * `S2_A5_ISUNIX_G0_CHARACTERIZATION_RECEIPT.md`): `osName.lowercase()` contains any of
 * `linux | mac | darwin | freebsd`. The DSL-side whitelist (PATH_A) is deliberately NOT
 * copied; the canonical-policy decision belongs to G2 with a clean `C == B` baseline.
 *
 * Capability separation (the G0 live counterexample made this justified):
 * - [PLATFORM_IDENTITY_CAPABILITY] owns the environmental OBSERVATION (`osName`);
 *   the single `System.getProperty("os.name")` read lives in the capability bridge,
 *   never in the handler.
 * - The Step owns the isUnix classification POLICY.
 * - [EVENT_SINK_CAPABILITY] publishes the durable `UnixDetected` observation.
 *
 * Adjective checks: input codec preserves the legacy `"{}"` envelope; the handler is
 * total (no exceptions as semantics); `UnixDetected` fields mirror the legacy dispatcher
 * byte-for-byte (uuid eventId, sequence 0L, sha256 hex of osName).
 */
object CoreIsUnixStep {

    val KEY: PluginStepId = PluginStepId("core.isUnix")

    /**
     * PATH_B classifier, verbatim from `CanonicalIsUnixNodeDispatcher.dispatch`
     * (G0 characterization). Single source for both the typed result and the event so
     * they can never disagree within one invocation.
     */
    internal fun classify(osName: String): Boolean =
        osName.lowercase().let {
            it.contains("linux") || it.contains("mac") || it.contains("darwin") || it.contains("freebsd")
        }

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

    internal fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

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
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
