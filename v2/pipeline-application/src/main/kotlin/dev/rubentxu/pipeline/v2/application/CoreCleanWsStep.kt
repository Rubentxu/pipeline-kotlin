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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed input payload of `core.cleanWs` (LFC-2E1-S2-A10 / G1).
 *
 * The legacy wire payload for `core.cleanWs` is
 * `{"kind":"cleanWs","deleteDirs":<bool>,"patterns":[...]}` — the compiler
 * `encodePayload` else-branch (`declarativeValue`) is NEVER reached for
 * cleanWs: the generic `{"kind": step.name, ...}` projection plus a
 * `data class StepSpec.CleanWs` `toString()` carry the fields. The legacy
 * decoder (`CanonicalCoreStepDecoder` CLEAN_WS_PLUGIN_ID branch) accepts the
 * same envelope with tolerant defaults: `deleteDirs` defaults to `true` and a
 * missing/null `patterns` array defaults to an empty list.
 */
data class CleanWsInput(
    val deleteDirs: Boolean = true,
    val patterns: List<String> = emptyList(),
)

/**
 * Typed runtime output of `core.cleanWs`.
 *
 * The legacy durable path produces `StepOutcome.Success` and emits `WsCleaned`
 * with deletedFiles, deletedDirs, patterns, and sha256. The candidate exposes
 * these as a typed result.
 *
 * Durable law: fresh/rerun CLEANS the workspace and emits a new `WsCleaned`
 * with current counts; resume/reuse REPRODUCES the persisted observation
 * (`CleanWsOutput` + original `WsCleaned`) without re-executing the handler.
 * Idempotence: cleaning an already-clean workspace SUCCEEDS with
 * deletedFiles=0 / deletedDirs=0.
 */
data class CleanWsOutput(
    val deletedFiles: Int,
    val deletedDirs: Int,
    val patterns: List<String>,
    val sha256: String,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Success
}

/**
 * Registry candidate for `core.cleanWs` (LFC-2E1-S2-A10 / G1).
 *
 * ## Architecture
 *
 * The handler is a thin typed seam — it receives [CleanWsInput] and returns
 * [CleanWsOutput]. Zero infrastructure in the handler:
 * - NO `CleanWsExecutor` construction
 * - NO `EventSink` access
 * - NO `Files.*` calls
 * - NO sha256 computation (comes from typed operation result)
 *
 * All filesystem semantics, workspace resolution, and event emission live in
 * [dev.rubentxu.pipeline.v2.application.durable.CleanWsOperationsAdapter],
 * accessed via [CLEAN_WS_OPERATIONS_CAPABILITY]. This follows the certified
 * pattern of `CoreDeleteDirStep` / `DeleteDirOperations` (S2-A7 / G3-fix).
 *
 * ## Capability discipline
 *
 * [CLEAN_WS_OPERATIONS_CAPABILITY] is the ONLY declared capability. The handler
 * does NOT consume `EVENT_SINK_CAPABILITY` or `STAGE_IDENTITY_CAPABILITY`
 * directly — those are bound by the adapter, which is the single `WsCleaned`
 * emission authority (no duplicate events; console/transcript rule analogue).
 *
 * ## Conditional exposure
 *
 * The capability is only registered into the runtime capability table when
 * `controlDirRoot != null` (eager null-check at capability-access construction).
 * If `controlDirRoot` is absent, capability admission fails closed for
 * `core.cleanWs` and the step is never dispatched — the rest of the registry
 * is unaffected.
 *
 * ## Classification
 *
 * Effectful (workspace-mutating, WRITES_WORKSPACE), non-recoverable
 * (RecoveryPolicy.None). ReplayPolicy MEMOIZED matches the legacy metadata row
 * (`CanonicalCoreStepMetadata["core.cleanWs"]`). While `core.cleanWs` remains
 * in LEGACY_PLUGIN_IDS, StructuralFamilyResolver's legacy-membership-wins rule
 * keeps LegacyCore as the production authority; this registration is
 * candidate-only (G1).
 */
object CoreCleanWsStep {

    val KEY: PluginStepId = PluginStepId("core.cleanWs")

    private val json = Json { encodeDefaults = false }

    private val inputCodec = object : StepCodec<CleanWsInput> {
        override fun encode(value: CleanWsInput): EncodedStepValue =
            EncodedStepValue(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("cleanWs"))
                        put("deleteDirs", JsonPrimitive(value.deleteDirs))
                        put("patterns", buildJsonArray {
                            value.patterns.forEach { add(JsonPrimitive(it)) }
                        })
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): CleanWsInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "cleanWs") {
                "core.cleanWs payload kind must be 'cleanWs'"
            }
            val deleteDirs = obj["deleteDirs"]?.jsonPrimitive?.booleanOrNull ?: true
            val patterns = obj["patterns"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()
            return CleanWsInput(deleteDirs = deleteDirs, patterns = patterns)
        }
    }

    private val outputCodec = object : StepCodec<CleanWsOutput> {
        override fun encode(value: CleanWsOutput): EncodedStepValue =
            EncodedStepValue(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("cleanWs"))
                        put("deletedFiles", JsonPrimitive(value.deletedFiles))
                        put("deletedDirs", JsonPrimitive(value.deletedDirs))
                        put("patterns", buildJsonArray {
                            value.patterns.forEach { add(JsonPrimitive(it)) }
                        })
                        put("sha256", JsonPrimitive(value.sha256))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): CleanWsOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "cleanWs") {
                "core.cleanWs output payload kind must be 'cleanWs'"
            }
            return CleanWsOutput(
                deletedFiles = obj.getValue("deletedFiles").jsonPrimitive.content.toInt(),
                deletedDirs = obj.getValue("deletedDirs").jsonPrimitive.content.toInt(),
                patterns = obj.getValue("patterns").jsonArray.map { it.jsonPrimitive.content },
                sha256 = obj.getValue("sha256").jsonPrimitive.content,
            )
        }
    }

    // Byte-equivalent to the legacy CanonicalCoreStepMetadata["core.cleanWs"] row:
    // StepMetadata(setOf(Effect.WRITES_WORKSPACE), ReplayPolicy.MEMOIZED).
    private val descriptor = StepDescriptor(
        stepId = "core.cleanWs",
        name = "cleanWs",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.WRITES_WORKSPACE),
        replayPolicy = ReplayPolicy.MEMOIZED,
        recoveryPolicy = dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy.None,
    )

    /**
     * Thin handler — zero infrastructure.
     *
     * The handler reaches the typed [CleanWsOperations] seam via
     * [CLEAN_WS_OPERATIONS_CAPABILITY] and delegates to `ops.clean(input)`.
     * All filesystem semantics, workspace resolution, and `WsCleaned` emission
     * live in [dev.rubentxu.pipeline.v2.application.durable.CleanWsOperationsAdapter].
     */
    private val capabilityRoutedHandler: StepHandler<CleanWsInput, CleanWsOutput> =
        StepHandler { input, ctx ->
            val ops: CleanWsOperations = ctx.capabilities.get(CLEAN_WS_OPERATIONS_CAPABILITY)
            val result = ops.clean(input)
            CleanWsOutput(
                deletedFiles = result.deletedFiles,
                deletedDirs = result.deletedDirs,
                patterns = result.patterns,
                sha256 = result.sha256,
            )
        }

    val definition: StepDefinition<CleanWsInput, CleanWsOutput> =
        object : StepDefinition<CleanWsInput, CleanWsOutput> {
            override val contract: StepContract<CleanWsInput, CleanWsOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                // Single capability — all workspace resolution, execution, and
                // WsCleaned emission are bound by the adapter.
                requiredCapabilities = setOf(CLEAN_WS_OPERATIONS_CAPABILITY) as Set<StepCapability>,
            )

            override val handler: StepHandler<CleanWsInput, CleanWsOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
