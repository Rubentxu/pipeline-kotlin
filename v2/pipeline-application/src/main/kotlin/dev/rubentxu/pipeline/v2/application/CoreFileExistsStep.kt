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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed input payload of `core.fileExists` (WU-LPR-104).
 *
 * Decoding validates the complete canonical envelope before the handler can create an
 * effect: non-blank `file`.
 */
data class CoreFileExistsInput(val file: String) {
    init {
        require(file.isNotBlank()) { "core.fileExists requires a non-blank file" }
    }
}

/**
 * Typed successful output for `core.fileExists` (WU-LPR-087, LFC-2R2).
 *
 * Jenkins semantics: `fileExists` is a predicate, NOT an assertion — `exists == false`
 * is a legitimate success outcome, not a step failure. The runtime-returning façade
 * decodes this typed field back to `Boolean` for the scripted caller.
 */
data class CoreFileExistsOutput(
    val exists: Boolean,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Success
}

/**
 * Registry authority for `core.fileExists` (WU-LPR-104, blocker closure for the
 * LPR certification checkpoint).
 *
 * The handler reaches workspace file operations ONLY through the typed
 * [WorkspaceOperations] seam (capability `WORKSPACE_OPERATIONS_CAPABILITY`), which binds
 * the certified `FileExistsExecutor` substrate. The handler:
 *
 *  - does NOT probe the filesystem itself,
 *  - does NOT emit `FileExistsChecked` (the adapter is the single emitter),
 *  - does NOT resolve stage identity (the adapter binds the runtime context's values).
 *
 * Capability admission is fail-closed at prepare-time via `RegistryExecutionPreparation`.
 */
object CoreFileExistsStep {
    val KEY: PluginStepId = PluginStepId("core.fileExists")

    /**
     * Canonical dsl-v1 envelope, byte-identical to the compile lowering emitted by
     * `DslCompiledPipelineCompiler.fileExistsPayload`:
     * `{"kind":"fileExists","file":...}`.
     */
    private val inputCodec = object : StepCodec<CoreFileExistsInput> {
        override fun encode(value: CoreFileExistsInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("kind", JsonPrimitive("fileExists"))
                    put("file", JsonPrimitive(value.file))
                }),
            )

        override fun decode(encoded: EncodedStepValue): CoreFileExistsInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "fileExists") {
                "core.fileExists payload kind must be 'fileExists'"
            }
            return CoreFileExistsInput(
                file = obj.getValue("file").jsonPrimitive.content,
            )
        }
    }

    private val outputCodec = object : StepCodec<CoreFileExistsOutput> {
        override fun encode(value: CoreFileExistsOutput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("fileExists"))
                        put("exists", JsonPrimitive(value.exists))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): CoreFileExistsOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "fileExists") {
                "core.fileExists output payload kind must be 'fileExists'"
            }
            // Back-compat: legacy envelope `{"kind":"fileExists","outcome":"SUCCESS"}` has no
            // `exists` field — decode as exists=true (the original Unit-only step never
            // observed absence, so the legacy wire implies success-implies-exists).
            val exists = obj["exists"]?.jsonPrimitive?.booleanOrNull ?: true
            return CoreFileExistsOutput(exists = exists)
        }
    }

    private val descriptor = StepDescriptor(
        stepId = "core.fileExists",
        name = "fileExists",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val capabilityRoutedHandler: StepHandler<CoreFileExistsInput, CoreFileExistsOutput> =
        StepHandler { input, ctx ->
            val ops: WorkspaceOperations = ctx.capabilities.get(WORKSPACE_OPERATIONS_CAPABILITY)
            val result = ops.fileExists(file = input.file)
            CoreFileExistsOutput(exists = result.exists)
        }

    val definition: StepDefinition<CoreFileExistsInput, CoreFileExistsOutput> =
        object : StepDefinition<CoreFileExistsInput, CoreFileExistsOutput> {
            override val contract: StepContract<CoreFileExistsInput, CoreFileExistsOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(WORKSPACE_OPERATIONS_CAPABILITY),
            )

            override val handler: StepHandler<CoreFileExistsInput, CoreFileExistsOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
