package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed input payload of `core.error`.
 *
 * The whole input is encoded into the canonical dsl-v1 envelope by [inputCodec]. No field
 * is encoded separately; the codec encodes the WHOLE payload so the journal/fingerprint
 * identity of `core.error` is independent of the codec's internal layout.
 */
data class CoreErrorInput(
    val message: String,
    val failureKind: FailureKind,
) {
    init {
        require(message.isNotBlank()) {
            "core.error message must not be blank (PipelineFailure invariant)"
        }
    }
}

/**
 * `core.error` registered as an open [StepDefinition] (B1.2b, ADR-0070).
 *
 * Mirrors the structure of [CoreEchoStep] (atomic) and [CoreShellStep] (effectful via
 * `TypedStepOutput`). The handler returns a [CoreErrorOutput] (a [TypedStepOutput] carrier)
 * — it MUST NOT throw to signal a failure. The boundary folds `produced as? TypedStepOutput`
 * into the canonical `CommonExecutionResult.outcome` (Step-agnostic).
 *
 * S2-A1 / G3-A4.3 amendment: the carrier's invariant
 * `outcome == StepOutcome.Failure(failure)` is enforced in [CoreErrorOutput]'s factory
 * (`from`/`of`). The handler does NOT compute the outcome; it calls [CoreErrorOutput.of]
 * and the boundary projects `outcome` unchanged.
 *
 * Required capabilities: `emptySet()`. The handler does not reach a coordinator, journal,
 * event sink, or process executor. The boundary is the sole authority for
 * `StepStarted`/`StepFailed`/`StepFinished`/`RunFinished` event projection.
 */
object CoreErrorStep {

    val KEY: PluginStepId = PluginStepId("core.error")

    /**
     * Input codec: encodes the WHOLE input payload as a dsl-v1 JSON envelope. The encoded
     * shape is byte-identical to what the legacy `CanonicalCoreStepDecoder` produced for
     * `core.error` (`{"kind":"error","message":"...","failureKind":"USER"}`), so durable
     * fingerprint/journal identity is continuous across the migration.
     *
     * Round-trip `encode(I) → payload.encoded → decode` is lossless. Rejects unknown
     * `failureKind` names fail-closed (matches legacy behavior).
     */
    private val inputCodec = object : StepCodec<CoreErrorInput> {
        override fun encode(value: CoreErrorInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("kind", JsonPrimitive("error"))
                    put("message", JsonPrimitive(value.message))
                    put("failureKind", JsonPrimitive(value.failureKind.name))
                }),
            )

        override fun decode(encoded: EncodedStepValue): CoreErrorInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "error") {
                "core.error payload kind must be 'error'"
            }
            val message = obj.getValue("message").jsonPrimitive.content
            val kindName = obj.getValue("failureKind").jsonPrimitive.content
            val kind = FailureKind.entries.firstOrNull { it.name == kindName }
                ?: throw IllegalArgumentException("Unknown failure kind '$kindName' for core.error")
            return CoreErrorInput(message, kind)
        }
    }

    /**
     * Output codec: encodes the canonical [CoreErrorOutput] as a dsl-v1 JSON envelope carrying
     * only the contractual fields required for deterministic decode:
     *  - `kind` = `"error"` (discriminant)
     *  - `failureKind` = [FailureKind.name]
     *  - `failureMessage` = [PipelineFailure.message]
     *
     * `outcome` is NOT serialized (it is reconstructed from `failureKind` + `failureMessage`
     * via [CoreErrorOutput.from], which is the single failure authority). Stack traces,
     * Throwable instances, or any non-contractual fields are NEVER encoded. The codec is
     * round-trip deterministic: `encode(decode(encode(x))) == encode(x)` byte-for-byte.
     */
    private val outputCodec = object : StepCodec<CoreErrorOutput> {
        override fun encode(value: CoreErrorOutput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("kind", JsonPrimitive("error"))
                    put("failureKind", JsonPrimitive(value.failure.kind.name))
                    put("failureMessage", JsonPrimitive(value.failure.message))
                }),
            )

        override fun decode(encoded: EncodedStepValue): CoreErrorOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "error") {
                "core.error output payload kind must be 'error'"
            }
            val kindName = obj.getValue("failureKind").jsonPrimitive.content
            val kind = FailureKind.entries.firstOrNull { it.name == kindName }
                ?: throw IllegalArgumentException("Unknown failure kind '$kindName' for core.error output")
            val message = obj.getValue("failureMessage").jsonPrimitive.content
            // Reconstruct via the single authority (CoreErrorOutput.from); no parallel
            // classifier is reintroduced on decode.
            return CoreErrorOutput.from(PipelineFailure(kind, message))
        }
    }

    /**
     * Descriptor preserved from the legacy metadata row:
     * `StepMetadata(setOf(Effect.ABORTS_PIPELINE), ReplayPolicy.NEVER)`.
     *
     * `Effect.ABORTS_PIPELINE` is the Step's effect AFTER legitimate execution: a `core.error`
     * outcome aborts the pipeline (no later step executes). This is the typed outcome side
     * and is distinct from the replay decision (see [EffectReplayPolicy]).
     *
     * `ReplayPolicy.NEVER` is the replay decision:
     *  - fresh / no durable entry → execute handler once (typed failure);
     *  - existing durable history → `ReplayDecision.ABORT`; handler NEVER runs again.
     * This is the same semantics as the legacy path, derived from the Step's real behavior
     * (no reproducible effect to re-execute), not copied from echo/sh.
     */
    private val descriptor = StepDescriptor(
        stepId = "core.error",
        name = "error",
        configRef = "",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = listOf(Effect.ABORTS_PIPELINE),
        replayPolicy = ReplayPolicy.NEVER,
    )

    /**
     * The single canonical [StepDefinition] for `core.error`. The handler does NOT throw
     * to signal failure; it returns a [CoreErrorOutput] whose invariant is enforced in
     * the carrier's constructor and whose `outcome` is the single authority for the
     * `StepOutcome.Failure(failure)` projection.
     */
    val definition: StepDefinition<CoreErrorInput, CoreErrorOutput> = object :
        StepDefinition<CoreErrorInput, CoreErrorOutput> {

        override val contract: StepContract<CoreErrorInput, CoreErrorOutput> = StepContract(
            key = KEY,
            descriptor = descriptor,
            inputCodec = inputCodec,
            outputCodec = outputCodec,
            requiredCapabilities = emptySet(),
        )

        override val handler: StepHandler<CoreErrorInput, CoreErrorOutput> =
            StepHandler { input, _ ->
                // The handler is the only place where the typed input becomes a typed output.
                // It uses the single authority (CoreErrorOutput.of) — no parallel outcome
                // classifier, no exception-as-control-flow. The boundary reads `outcome` via
                // `produced as? TypedStepOutput` (Step-agnostic).
                CoreErrorOutput.of(input.failureKind, input.message)
            }
    }

    /**
     * Registers this Step into a [StepRegistry]. NOT yet wired into
     * [CoreStepRegistryFactory] (G2); until then `core.error` remains LEGACY_EXECUTABLE
     * and `LEGACY_PLUGIN_IDS == 12`. Wiring happens in G2 after handler/codec/contract
     * unit tests pass.
     */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
