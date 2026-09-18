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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed input payload of `core.readFile` (WU-LPR-104).
 *
 * Decoding validates the complete canonical envelope before the handler can create an
 * effect: non-blank `file`, non-null `encoding`.
 */
data class CoreReadFileInput(val file: String, val encoding: String) {
    init {
        require(file.isNotBlank()) { "core.readFile requires a non-blank file" }
    }
}

/**
 * Typed successful output for `core.readFile` (statement semantics: the DSL `readFile`
 * fun returns Unit today; the typed value exists so the durable channel never erases
 * the step result shape).
 *
 * Observable read evidence is the `FileRead` domain event emitted by the workspace
 * operations adapter (path, sha256, size — NEVER content, INV-L6-EVT-001). Missing or
 * guarded files surface as a typed step failure from the substrate, not silent success.
 */
data object CoreReadFileOutput : TypedStepOutput {
    override val outcome: StepOutcome = StepOutcome.Success
}

/**
 * Registry authority for `core.readFile` (WU-LPR-104, blocker closure for the
 * LPR certification checkpoint).
 *
 * The handler reaches workspace file operations ONLY through the typed
 * [WorkspaceOperations] seam (capability `WORKSPACE_OPERATIONS_CAPABILITY`), which binds
 * the certified `FileReadExecutor` substrate. The handler:
 *
 *  - does NOT read filesystem state itself,
 *  - does NOT emit `FileRead` (the adapter is the single emitter),
 *  - does NOT resolve stage identity (the adapter binds the runtime context's values).
 *
 * Capability admission is fail-closed at prepare-time via `RegistryExecutionPreparation`.
 */
object CoreReadFileStep {
    val KEY: PluginStepId = PluginStepId("core.readFile")

    /**
     * Canonical dsl-v1 envelope, byte-identical to the compile lowering emitted by
     * `DslCompiledPipelineCompiler.readFilePayload`:
     * `{"kind":"readFile","file":...,"encoding":...}`.
     */
    private val inputCodec = object : StepCodec<CoreReadFileInput> {
        override fun encode(value: CoreReadFileInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("kind", JsonPrimitive("readFile"))
                    put("file", JsonPrimitive(value.file))
                    put("encoding", JsonPrimitive(value.encoding))
                }),
            )

        override fun decode(encoded: EncodedStepValue): CoreReadFileInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "readFile") {
                "core.readFile payload kind must be 'readFile'"
            }
            return CoreReadFileInput(
                file = obj.getValue("file").jsonPrimitive.content,
                encoding = obj.getValue("encoding").jsonPrimitive.content,
            )
        }
    }

    private val outputCodec = object : StepCodec<CoreReadFileOutput> {
        override fun encode(value: CoreReadFileOutput): EncodedStepValue =
            EncodedStepValue("{\"kind\":\"readFile\",\"outcome\":\"SUCCESS\"}")

        override fun decode(encoded: EncodedStepValue): CoreReadFileOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "readFile") {
                "core.readFile output payload kind must be 'readFile'"
            }
            require(obj["outcome"]?.jsonPrimitive?.content == "SUCCESS") {
                "core.readFile output outcome must be 'SUCCESS'"
            }
            return CoreReadFileOutput
        }
    }

    private val descriptor = StepDescriptor(
        stepId = "core.readFile",
        name = "readFile",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val capabilityRoutedHandler: StepHandler<CoreReadFileInput, CoreReadFileOutput> =
        StepHandler { input, ctx ->
            val ops: WorkspaceOperations = ctx.capabilities.get(WORKSPACE_OPERATIONS_CAPABILITY)
            ops.readFile(file = input.file, encoding = input.encoding)
            CoreReadFileOutput
        }

    val definition: StepDefinition<CoreReadFileInput, CoreReadFileOutput> =
        object : StepDefinition<CoreReadFileInput, CoreReadFileOutput> {
            override val contract: StepContract<CoreReadFileInput, CoreReadFileOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(WORKSPACE_OPERATIONS_CAPABILITY),
            )

            override val handler: StepHandler<CoreReadFileInput, CoreReadFileOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
