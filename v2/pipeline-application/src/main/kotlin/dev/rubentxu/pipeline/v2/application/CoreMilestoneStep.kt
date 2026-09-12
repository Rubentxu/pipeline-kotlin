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
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.util.UUID

/**
 * Typed input payload of `core.milestone`.
 *
 * Mirrors the legacy dsl-v1 envelope produced by `DslCompiledPipelineCompiler.milestonePayload`:
 * `{"kind":"milestone","ordinal":N,"label":"optional-label-or-absent"}`.
 *
 * The codec encodes/decodes the ordinal (required, positive Int) and the optional label.
 * Validation: ordinal MUST be > 0 (fail-closed at decode time).
 */
data class MilestoneInput(
    val ordinal: Int,
    val label: String?,
) {
    init {
        require(ordinal > 0) {
            "core.milestone requires a positive ordinal, got: $ordinal"
        }
    }
}

/**
 * TYPED_RUNTIME_OUTPUT — milestone does not produce a consumable runtime value (unlike `core.pwd`);
 * this type serves as the typed output carrier for the pipeline engine, carrying the ordinal,
 * label, and the reach/abort status as a sealed ADT.
 */
data class MilestoneOutput(
    val ordinal: Int,
    val label: String?,
    val status: MilestoneStatus,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = when (status) {
        is MilestoneStatus.Reached -> StepOutcome.Success
        is MilestoneStatus.Aborted -> StepOutcome.Unstable
    }
}

/**
 * Closed outcome algebra for a milestone invocation.
 *
 * A milestone either:
 * - [Reached]: the ordinal was strictly greater than the previously reached ordinal
 *   within this pipeline run → [MilestoneReached] event emitted.
 * - [Aborted]: the ordinal was not greater than the previously reached ordinal
 *   within this pipeline run → [MilestoneAborted] event emitted.
 *
 * In the local single-run model, an aborted milestone records the event and returns
 * [StepOutcome.Unstable] but does NOT abort the pipeline (per ML-R9 T-09, ADR-0046 §ML).
 */
sealed class MilestoneStatus {
    abstract val kind: String

    /** The ordinal was strictly greater than the previously reached ordinal. */
    object Reached : MilestoneStatus() {
        override val kind: String get() = "reached"
    }

    /**
     * The ordinal was not strictly greater than the previously reached ordinal.
     * @property reason Human-readable reason for abortion (e.g. "ordinal-already-reached").
     */
    data class Aborted(val reason: String) : MilestoneStatus() {
        override val kind: String get() = "aborted"
    }
}

/**
 * Registry candidate for `core.milestone` (S2-A9 / G1 registry seam proof).
 *
 * G1 registers this candidate WITHOUT changing `LEGACY_PLUGIN_IDS`, the legacy
 * decoder, the metadata row, or the legacy dispatcher:
 * `StructuralFamilyResolver`'s legacy-membership-wins rule keeps LegacyCore as
 * the production authority until the G3/G4 flip. Counters stay 12 / 12 / 12.
 *
 * **Semantics (per Jenkins verbatim, ADR-0046 §ML / ML-R9 T-09):**
 * - Strictly increasing ordinal (within this pipeline run) → MilestoneReached event + Success.
 * - Non-increasing ordinal → MilestoneAborted event + Unstable (record-only, never aborts run).
 *
 * **State tracking:** the handler tracks `lastReachedOrdinal` in its companion object,
 * which is scoped per `CoreMilestoneStep` classloader instance. In the local single-run
 * model (no cross-build coordination), this is the correct scope: each pipeline run
 * gets its own coordinator instance, each coordinator instance gets its own handler
 * classloader instance, and state is isolated per run.
 *
 * **Capability separation:**
 * - [EVENT_SINK_CAPABILITY] publishes the durable MilestoneReached/MilestoneAborted observation.
 *   The handler reaches the event sink ONLY through the declared capability.
 *
 * **Input codec:** encodes `{"kind":"milestone","ordinal":N,"label":...}` — byte-identical
 * to the legacy `DslCompiledPipelineCompiler.milestonePayload` output, ensuring durable
 * fingerprint continuity across the eventual flip.
 *
 * **Output codec:** encodes the MilestoneOutput as JSON
 * `{"ordinal":N,"label":?,"status":"reached"|"aborted","reason":"..."}` for durable
 * eligibility (MEMOIZED replay needs a well-formed JSON object).
 */
object CoreMilestoneStep {

    val KEY: PluginStepId = PluginStepId("core.milestone")

    /**
     * Tracks the last reached ordinal within a single pipeline run.
     *
     * Scoped per `CoreMilestoneStep` classloader instance: in the local single-run
     * model, each `CanonicalDurableRunCoordinator` instance gets its own classloader,
     * so state is isolated per run. This mirrors the legacy
     * `CanonicalMilestoneNodeDispatcher.lastReachedOrdinal: Int?` in-memory state
     * but moves it into the handler's own scope (the handler is the new authority
     * for milestone semantics, not the coordinator's dispatcher).
     */
    private var lastReachedOrdinal: Int? = null

    private val inputCodec = object : StepCodec<MilestoneInput> {
        override fun encode(value: MilestoneInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("milestone"))
                        put("ordinal", JsonPrimitive(value.ordinal))
                        // label is omitted when null to match legacy behavior
                        value.label?.let { put("label", JsonPrimitive(it)) }
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): MilestoneInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "milestone") {
                "core.milestone payload kind must be 'milestone'"
            }
            val ordinal = obj.getValue("ordinal").jsonPrimitive.content.toIntOrNull()
                ?: throw IllegalArgumentException("core.milestone ordinal must be an integer")
            require(ordinal > 0) {
                "core.milestone requires a positive ordinal, got: $ordinal"
            }
            return MilestoneInput(
                ordinal = ordinal,
                label = obj["label"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    private val outputCodec = object : StepCodec<MilestoneOutput> {
        override fun encode(value: MilestoneOutput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("ordinal", JsonPrimitive(value.ordinal))
                        value.label?.let { put("label", JsonPrimitive(it)) }
                        put("status", JsonPrimitive(value.status.kind))
                        if (value.status is MilestoneStatus.Aborted) {
                            put("reason", JsonPrimitive((value.status as MilestoneStatus.Aborted).reason))
                        }
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): MilestoneOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            val ordinal = obj.getValue("ordinal").jsonPrimitive.content.toIntOrNull()
                ?: throw IllegalArgumentException("ordinal must be an integer")
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
            val statusStr = obj.getValue("status").jsonPrimitive.content
            val status = when (statusStr) {
                "reached" -> MilestoneStatus.Reached
                "aborted" -> MilestoneStatus.Aborted(
                    obj["reason"]?.jsonPrimitive?.content ?: "ordinal-already-reached"
                )
                else -> throw IllegalArgumentException("unknown milestone status: $statusStr")
            }
            return MilestoneOutput(ordinal = ordinal, label = label, status = status)
        }
    }

    // Byte-equivalent to the legacy CanonicalCoreStepMetadata["core.milestone"] row.
    // effects={READ_ONLY}, replay=MEMOIZED, location=CONTROLLER.
    private val descriptor = StepDescriptor(
        stepId = "core.milestone",
        name = "milestone",
        configRef = "",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val capabilityRoutedHandler: StepHandler<MilestoneInput, MilestoneOutput> =
        StepHandler { input, ctx ->
            val sink: EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)
            val previous = lastReachedOrdinal
            if (previous != null && input.ordinal <= previous) {
                sink.append(
                    MilestoneAborted(
                        eventId = UUID.randomUUID().toString(),
                        runId = ctx.runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        ordinal = input.ordinal,
                        reason = "ordinal-already-reached (previous=$previous)",
                    ),
                )
                lastReachedOrdinal = previous // unchanged
                MilestoneOutput(
                    ordinal = input.ordinal,
                    label = input.label,
                    status = MilestoneStatus.Aborted("ordinal-already-reached (previous=$previous)"),
                )
            } else {
                lastReachedOrdinal = input.ordinal
                sink.append(
                    MilestoneReached(
                        eventId = UUID.randomUUID().toString(),
                        runId = ctx.runId.value,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        ordinal = input.ordinal,
                        label = input.label,
                    ),
                )
                MilestoneOutput(
                    ordinal = input.ordinal,
                    label = input.label,
                    status = MilestoneStatus.Reached,
                )
            }
        }

    val definition: StepDefinition<MilestoneInput, MilestoneOutput> =
        object : StepDefinition<MilestoneInput, MilestoneOutput> {
            override val contract: StepContract<MilestoneInput, MilestoneOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(
                    EVENT_SINK_CAPABILITY,
                ) as Set<StepCapability>,
            )

            override val handler: StepHandler<MilestoneInput, MilestoneOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
