package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

/**
 * Typed input payload of `core.emit.event` (S2-A4 / G1).
 *
 * Deliberately OPEN with respect to `kind`: the legacy wire decoder is total (any dsl-v1
 * payload with a `kind` string decodes), and the whitelist is SEMANTIC policy of the Step,
 * not of the wire codec. Keeping the whitelist in the handler preserves the legacy failure
 * layering — `unknown kind -> StepOutcome.Failure(SCHEMA)` — instead of shifting it to a
 * decode failure. The codec validates only the ENVELOPE STRUCTURE (`kind` present as a
 * string); it does NOT validate whitelist membership or field requirements.
 */
data class CoreEmitEventInput(
    val kind: String,
    val payload: Map<String, String?>,
)

/**
 * Typed output ADT for `core.emit.event` (S2-A4 / G1).
 *
 * The handler NEVER uses exceptions as normal semantics: every legitimate rejection
 * (unknown kind, missing `message`, missing/invalid FileWritten fields) surfaces as
 * [EmitEventRejected] carrying the typed [PipelineFailure], projected to
 * `StepOutcome.Failure` by the definition's result mapping.
 */
sealed interface CoreEmitEventOutput : TypedStepOutput {
    override val outcome: StepOutcome

    data object EmitEventSuccess : CoreEmitEventOutput {
        override val outcome: StepOutcome = StepOutcome.Success
    }

    data object EmitEventUnstable : CoreEmitEventOutput {
        override val outcome: StepOutcome = StepOutcome.Unstable
    }

    data class EmitEventRejected(val failure: PipelineFailure) : CoreEmitEventOutput {
        override val outcome: StepOutcome = StepOutcome.Failure(failure)
    }
}

/**
 * Registry candidate for `core.emit.event` (S2-A4 / G1 registry seam proof).
 *
 * G1 registers this candidate WITHOUT changing `LEGACY_PLUGIN_IDS`, the legacy decoder,
 * the metadata row, or the legacy dispatcher: `StructuralFamilyResolver`'s
 * legacy-membership-wins rule keeps LegacyCore as the production authority until the
 * G3/G4 flip.
 *
 * APPROVED FIX CANDIDATE (classification finalized at G2): the legacy dispatcher escapes
 * untyped `IllegalStateException`s from `error(...)` on valid-kind incomplete payloads
 * (`StageMarkedUnstable` without `message`, `FileWritten` missing fields or non-numeric
 * `size`), which the run loop maps to INFRASTRUCTURE. The candidate validates these in the
 * handler and produces typed [CoreEmitEventOutput.EmitEventRejected] with
 * `FailureKind.SCHEMA` and ZERO events. Legacy behaviour remains byte-compatible until
 * the flip.
 *
 * Whitelist authority is preserved verbatim (ADR-0054 §D6): all four kinds KEEP their
 * place in the supported contract; classification as internal marker / runtime-used /
 * dead callsite is a separate dimension, not a burn-down concern.
 *
 * Capabilities: the handler reaches the event sink ONLY through [EVENT_SINK_CAPABILITY]
 * and the current stage identity ONLY through [STAGE_IDENTITY_CAPABILITY] (narrow
 * `StageIdentity(name, index)`; `StepHandlerContext` stays free of stage data and the
 * runtime context is never handed to the handler).
 */
object CoreEmitEventStep {
    val KEY: PluginStepId = PluginStepId("core.emit.event")

    /** ADR-0054 §D6 whitelist, byte-equivalent to the legacy authority. */
    private val ALLOWED_KINDS = setOf(
        "CatchErrorEntered",
        "CatchErrorTriggered",
        "StageMarkedUnstable",
        "FileWritten",
    )

    /**
     * Canonical dsl-v1 envelope, byte-identical to the legacy decode form emitted by
     * `DslCompiledPipelineCompiler.emitEventPayload`: `kind` first, then fields in map
     * order, with null values encoded as JSON `null`.
     */
    private val inputCodec = object : StepCodec<CoreEmitEventInput> {
        override fun encode(value: CoreEmitEventInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("kind", JsonPrimitive(value.kind))
                    value.payload.forEach { (k, v) ->
                        if (v != null) put(k, JsonPrimitive(v)) else put(k, JsonNull)
                    }
                }),
            )

        override fun decode(encoded: EncodedStepValue): CoreEmitEventInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            // Envelope structure only: `kind` must be present as a string. Whitelist
            // membership is handler semantics (legacy layering preserved).
            val kind = obj.getValue("kind").jsonPrimitive.content
            val payload = obj.entries
                .filter { it.key != "kind" }
                .associate { it.key to it.value.jsonPrimitive.contentOrNull }
            return CoreEmitEventInput(kind, payload)
        }
    }

    private val outputCodec = object : StepCodec<CoreEmitEventOutput> {
        override fun encode(value: CoreEmitEventOutput): EncodedStepValue = EncodedStepValue(
            when (value) {
                CoreEmitEventOutput.EmitEventSuccess -> "{\"kind\":\"emitEvent\",\"outcome\":\"SUCCESS\"}"
                CoreEmitEventOutput.EmitEventUnstable -> "{\"kind\":\"emitEvent\",\"outcome\":\"UNSTABLE\"}"
                is CoreEmitEventOutput.EmitEventRejected ->
                    "{\"kind\":\"emitEvent\",\"outcome\":\"REJECTED\"}"
            },
        )

        override fun decode(encoded: EncodedStepValue): CoreEmitEventOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "emitEvent") {
                "core.emit.event output payload kind must be 'emitEvent'"
            }
            return when (obj.getValue("outcome").jsonPrimitive.content) {
                "SUCCESS" -> CoreEmitEventOutput.EmitEventSuccess
                "UNSTABLE" -> CoreEmitEventOutput.EmitEventUnstable
                else -> throw IllegalArgumentException("core.emit.event output outcome invalid")
            }
        }
    }

    // Byte-equivalent to the legacy CanonicalCoreStepMetadata["core.emit.event"] row.
    private val descriptor = StepDescriptor(
        stepId = "core.emit.event",
        name = "emitEvent",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    /**
     * Projects a typed output to a StepOutcome. Kept total: Rejected carries its own
     * PipelineFailure, so no exception mapping is needed on this path.
     */
    private val capabilityRoutedHandler: StepHandler<CoreEmitEventInput, CoreEmitEventOutput> =
        StepHandler { input, ctx ->
            if (input.kind !in ALLOWED_KINDS) {
                return@StepHandler CoreEmitEventOutput.EmitEventRejected(
                    PipelineFailure(
                        FailureKind.SCHEMA,
                        "core.emit.event kind='${input.kind}' is not in the canonical whitelist " +
                            "(${ALLOWED_KINDS.joinToString(" / ")})",
                    ),
                )
            }
            val sink: dev.rubentxu.pipeline.v2.events.EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)
            when (input.kind) {
                // Scope-only markers: the coordinator handles scope push/pop and publishes
                // the real CatchErrorTriggered event at the fold-walk (EM-5/EM-6 D5).
                "CatchErrorEntered" -> CoreEmitEventOutput.EmitEventSuccess
                "CatchErrorTriggered" -> CoreEmitEventOutput.EmitEventSuccess
                "StageMarkedUnstable" -> {
                    val message = input.payload["message"]
                        ?: return@StepHandler CoreEmitEventOutput.EmitEventRejected(
                            PipelineFailure(
                                FailureKind.SCHEMA,
                                "StageMarkedUnstable requires 'message' in payload",
                            ),
                        )
                    val stageIdentity = ctx.capabilities.get<StageIdentity>(STAGE_IDENTITY_CAPABILITY)
                    sink.append(
                        StageMarkedUnstable(
                            eventId = UUID.randomUUID().toString(),
                            runId = ctx.runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            stageName = input.payload["stageName"] ?: stageIdentity.name,
                            message = message,
                        ),
                    )
                    CoreEmitEventOutput.EmitEventUnstable
                }
                "FileWritten" -> {
                    val path = input.payload["path"]
                        ?: return@StepHandler fileWrittenRejected("path")
                    val sha256 = input.payload["sha256"]
                        ?: return@StepHandler fileWrittenRejected("sha256")
                    val size = input.payload["size"]?.toLongOrNull()
                        ?: return@StepHandler fileWrittenRejected("size")
                    val atomicallyMoved = input.payload["atomicallyMoved"]?.toBooleanStrictOrNull() ?: false
                    sink.append(
                        FileWritten(
                            eventId = UUID.randomUUID().toString(),
                            runId = ctx.runId.value,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            path = Paths.get(path),
                            sha256 = sha256,
                            size = size,
                            atomicallyMoved = atomicallyMoved,
                        ),
                    )
                    CoreEmitEventOutput.EmitEventSuccess
                }
                else -> CoreEmitEventOutput.EmitEventRejected(
                    PipelineFailure(
                        FailureKind.SCHEMA,
                        "core.emit.event kind='${input.kind}' is not in the canonical whitelist",
                    ),
                )
            }
        }

    private fun fileWrittenRejected(field: String): CoreEmitEventOutput.EmitEventRejected =
        CoreEmitEventOutput.EmitEventRejected(
            PipelineFailure(
                FailureKind.SCHEMA,
                "FileWritten requires '$field' in payload",
            ),
        )

    val definition: StepDefinition<CoreEmitEventInput, CoreEmitEventOutput> =
        object : StepDefinition<CoreEmitEventInput, CoreEmitEventOutput> {
            override val contract: StepContract<CoreEmitEventInput, CoreEmitEventOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(
                    EVENT_SINK_CAPABILITY,
                    STAGE_IDENTITY_CAPABILITY,
                ) as Set<StepCapability>,
            )

            override val handler: StepHandler<CoreEmitEventInput, CoreEmitEventOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
