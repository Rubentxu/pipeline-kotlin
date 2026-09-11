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
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed input payload of `core.file.writeFile` (S2-A3).
 *
 * Decoding validates the complete canonical envelope before the handler can create an
 * effect: non-blank `file`, non-null `encoding`.
 */
data class CoreWriteFileInput(val file: String, val text: String, val encoding: String) {
    init {
        require(file.isNotBlank()) { "core.file.writeFile requires a non-blank file" }
    }
}

/**
 * Typed successful output for `core.file.writeFile`.
 *
 * The observable write evidence is the `FileWritten` domain event emitted by the
 * [dev.rubentxu.pipeline.v2.sdk.files.FileWriteExecutor] substrate (path, sha256, size,
 * atomicallyMoved). The typed output carries no duplicated write data — the transcript and
 * the typed value are independent channels.
 */
data object CoreWriteFileOutput : TypedStepOutput {
    override val outcome: StepOutcome = StepOutcome.Success
}

/**
 * Registry authority for `core.file.writeFile` (S2-A3 / G1 registry seam proof).
 *
 * G1 registers this candidate without changing `LEGACY_PLUGIN_IDS`, the legacy decoder,
 * the metadata row, or the legacy dispatcher: `StructuralFamilyResolver`'s
 * legacy-membership-wins rule keeps LegacyCore as the production authority until the
 * G3/G4 flip.
 *
 * The handler reaches workspace file operations ONLY through the typed
 * [WorkspaceOperations] seam (capability `WORKSPACE_OPERATIONS_CAPABILITY`), which binds
 * the certified `FileWriteExecutor` substrate. The handler:
 *
 *  - does NOT write filesystem state itself,
 *  - does NOT emit `FileWritten` (the substrate is the single emitter),
 *  - does NOT resolve stage identity (the adapter binds the runtime context's values).
 *
 * Capability admission is fail-closed at prepare-time via `RegistryExecutionPreparation`.
 */
object CoreWriteFileStep {
    val KEY: PluginStepId = PluginStepId("core.file.writeFile")

    /**
     * Canonical dsl-v1 envelope, byte-identical to the legacy decode form emitted by
     * `DslCompiledPipelineCompiler.writeFilePayload`:
     * `{"kind":"writeFile","file":...,"text":...,"encoding":...}`.
     */
    private val inputCodec = object : StepCodec<CoreWriteFileInput> {
        override fun encode(value: CoreWriteFileInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("kind", JsonPrimitive("writeFile"))
                    put("file", JsonPrimitive(value.file))
                    put("text", JsonPrimitive(value.text))
                    put("encoding", JsonPrimitive(value.encoding))
                }),
            )

        override fun decode(encoded: EncodedStepValue): CoreWriteFileInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "writeFile") {
                "core.file.writeFile payload kind must be 'writeFile'"
            }
            return CoreWriteFileInput(
                file = obj.getValue("file").jsonPrimitive.content,
                text = obj.getValue("text").jsonPrimitive.content,
                encoding = obj.getValue("encoding").jsonPrimitive.content,
            )
        }
    }

    private val outputCodec = object : StepCodec<CoreWriteFileOutput> {
        override fun encode(value: CoreWriteFileOutput): EncodedStepValue =
            EncodedStepValue("{\"kind\":\"writeFile\",\"outcome\":\"SUCCESS\"}")

        override fun decode(encoded: EncodedStepValue): CoreWriteFileOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "writeFile") {
                "core.file.writeFile output payload kind must be 'writeFile'"
            }
            require(obj["outcome"]?.jsonPrimitive?.content == "SUCCESS") {
                "core.file.writeFile output outcome must be 'SUCCESS'"
            }
            return CoreWriteFileOutput
        }
    }

    // Byte-equivalent to the legacy CanonicalCoreStepMetadata["core.file.writeFile"] row.
    private val descriptor = StepDescriptor(
        stepId = "core.file.writeFile",
        name = "writeFile",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.WRITES_WORKSPACE),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val capabilityRoutedHandler: StepHandler<CoreWriteFileInput, CoreWriteFileOutput> =
        StepHandler { input, ctx ->
            val ops: WorkspaceOperations = ctx.capabilities.get(WORKSPACE_OPERATIONS_CAPABILITY)
            ops.writeFile(file = input.file, text = input.text, encoding = input.encoding)
            CoreWriteFileOutput
        }

    val definition: StepDefinition<CoreWriteFileInput, CoreWriteFileOutput> =
        object : StepDefinition<CoreWriteFileInput, CoreWriteFileOutput> {
            override val contract: StepContract<CoreWriteFileInput, CoreWriteFileOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(WORKSPACE_OPERATIONS_CAPABILITY),
            )

            override val handler: StepHandler<CoreWriteFileInput, CoreWriteFileOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
