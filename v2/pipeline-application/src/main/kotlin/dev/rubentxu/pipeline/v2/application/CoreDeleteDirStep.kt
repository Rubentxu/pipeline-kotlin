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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed input payload of `core.deleteDir` (S2-A7 / G3-fix).
 *
 * The legacy wire payload for `core.deleteDir` is `{"kind":"deleteDir","path":"."}`
 * (compiler `encodePayload` else-branch). The `path` field defaults to "." (current
 * stage workspace).
 *
 * deleteDir is atomic: it recursively deletes workspace contents, leaves the
 * workspace directory intact, and writes a `.deleted` marker for MEMOIZED replay.
 */
data class DeleteDirInput(
    val path: String = ".",
)

/**
 * Typed runtime output of `core.deleteDir`.
 *
 * The legacy durable path produces `StepOutcome.Success` and emits `DirDeleted`
 * with path, deletedCount, and sha256. The candidate exposes these as a typed
 * result because the product baseline requires `deleteDir` to be observable.
 *
 * Durable law: fresh/rerun DELETES contents and emits new DirDeleted with
 * current deletedCount; resume/reuse REPRODUCES the persisted observation
 * (`DeleteDirOutput` + original `DirDeleted`) without re-executing the handler.
 */
data class DeleteDirOutput(
    val path: String,
    val deletedCount: Int,
    val sha256: String,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Success
}

/**
 * Registry candidate for `core.deleteDir` (S2-A7 / G3-fix).
 *
 * ## Architecture (G3-fix)
 *
 * The handler is a thin typed seam — it receives [DeleteDirInput] and returns
 * [DeleteDirOutput]. Zero infrastructure in the handler:
 * - NO `DeleteDirExecutor` construction
 * - NO `EventSink` access
 * - NO `Files.*` calls
 * - NO sha256 computation (comes from typed operation result)
 *
 * All filesystem semantics, workspace resolution, and event emission live in
 * [DeleteDirOperationsAdapter], accessed via [DELETE_DIR_OPERATIONS_CAPABILITY].
 *
 * This follows the certified pattern of:
 * - `WorkspaceOperations` / `WorkspaceOperationsAdapter` (S2-A3 / G1)
 * - `TemporaryWorkspaceOperations` / `TemporaryWorkspaceOperationsAdapter` (S2-A6 / G3T)
 * - `ShellOperations` / `ShOperationsAdapter` (LB-02 / G3)
 *
 * ## Capability discipline
 *
 * [DELETE_DIR_OPERATIONS_CAPABILITY] is the ONLY declared capability. The handler
 * does NOT consume `EVENT_SINK_CAPABILITY` or `STAGE_IDENTITY_CAPABILITY` directly —
 * those are bound by [dev.rubentxu.pipeline.v2.application.durable.DeleteDirOperationsAdapter].
 *
 * ## Conditional exposure
 *
 * The capability is only registered into the runtime capability table when
 * `controlDirRoot != null` (eager null-check at capability-access construction).
 * If `controlDirRoot` is absent, capability admission fails closed for
 * `core.deleteDir` and the step is never dispatched — the rest of the registry
 * is unaffected.
 *
 * ## Re-entry (G1 → G3-fix)
 *
 * G1 registered the candidate with three capabilities. G3-fix refactors to the
 * single-capability pattern per ADR-0070..0074 and AGENTS.md STEP CONSTITUTION.
 * Counters remain 6 / 6 / 6 (LEGACY_PLUGIN_IDS unchanged until G4).
 */
object CoreDeleteDirStep {

    val KEY: PluginStepId = PluginStepId("core.deleteDir")

    private val inputCodec = object : StepCodec<DeleteDirInput> {
        override fun encode(value: DeleteDirInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("deleteDir"))
                        put("path", JsonPrimitive(value.path))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): DeleteDirInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "deleteDir") {
                "core.deleteDir payload kind must be 'deleteDir'"
            }
            val path = obj["path"]?.jsonPrimitive?.content ?: "."
            return DeleteDirInput(path = path)
        }
    }

    private val outputCodec = object : StepCodec<DeleteDirOutput> {
        override fun encode(value: DeleteDirOutput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("deleteDir"))
                        put("path", JsonPrimitive(value.path))
                        put("deletedCount", JsonPrimitive(value.deletedCount))
                        put("sha256", JsonPrimitive(value.sha256))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): DeleteDirOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "deleteDir") {
                "core.deleteDir output payload kind must be 'deleteDir'"
            }
            return DeleteDirOutput(
                path = obj.getValue("path").jsonPrimitive.content,
                deletedCount = obj.getValue("deletedCount").jsonPrimitive.content.toInt(),
                sha256 = obj.getValue("sha256").jsonPrimitive.content,
            )
        }
    }

    // Byte-equivalent to the legacy CanonicalCoreStepMetadata["core.deleteDir"] row.
    private val descriptor = StepDescriptor(
        stepId = "core.deleteDir",
        name = "deleteDir",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.WRITES_WORKSPACE),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    /**
     * Thin handler — zero infrastructure.
     *
     * The handler reaches the typed [DeleteDirOperations] seam via
     * [DELETE_DIR_OPERATIONS_CAPABILITY] and delegates to `ops.delete(input)`.
     * All filesystem semantics, workspace resolution, and `DirDeleted` emission
     * live in [dev.rubentxu.pipeline.v2.application.durable.DeleteDirOperationsAdapter].
     *
     * Handler contract:
     * - Declares exactly ONE capability: [DELETE_DIR_OPERATIONS_CAPABILITY]
     * - Receives [DeleteDirInput], returns [DeleteDirOutput]
     * - Never constructs [dev.rubentxu.pipeline.v2.sdk.files.DeleteDirExecutor]
     * - Never accesses [dev.rubentxu.pipeline.v2.events.EventSink]
     * - Never calls `Files.*` or computes sha256
     */
    private val capabilityRoutedHandler: StepHandler<DeleteDirInput, DeleteDirOutput> =
        StepHandler { input, ctx ->
            val ops: DeleteDirOperations = ctx.capabilities.get(DELETE_DIR_OPERATIONS_CAPABILITY)
            val result = ops.delete(input)
            DeleteDirOutput(
                path = result.path,
                deletedCount = result.deletedCount,
                sha256 = result.sha256,
            )
        }

    val definition: StepDefinition<DeleteDirInput, DeleteDirOutput> =
        object : StepDefinition<DeleteDirInput, DeleteDirOutput> {
            override val contract: StepContract<DeleteDirInput, DeleteDirOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                // G3-fix: single capability — all workspace resolution, execution,
                // and event emission are bound by the adapter.
                requiredCapabilities = setOf(DELETE_DIR_OPERATIONS_CAPABILITY) as Set<StepCapability>,
            )

            override val handler: StepHandler<DeleteDirInput, DeleteDirOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
