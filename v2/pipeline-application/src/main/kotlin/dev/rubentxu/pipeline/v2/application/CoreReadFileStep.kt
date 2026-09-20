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
 * Typed successful output for `core.readFile` (WU-LPR-087, LFC-2R2).
 *
 * `content` is the file content as String (null when the file does not exist
 * — the substrate's path guard returns `exists=false` for out-of-workspace,
 * reserved-`.v2`, or missing targets). `exists = false` is a successful
 * observation in Jenkins predicate semantics, NOT a typed step failure.
 *
 * Observable read evidence is the `FileRead` domain event emitted by the
 * [WorkspaceOperationsAdapter] (path, sha256, size — NEVER content,
 * INV-L6-EVT-001). The content stays inside the typed step result and the
 * codec's canonical envelope; the runtime-returning façade decodes it back
 * to `String` for the scripted caller.
 */
data class CoreReadFileOutput(
    val content: String?,
    val exists: Boolean,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Success
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
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("readFile"))
                        value.content?.let { put("content", JsonPrimitive(it)) }
                        put("exists", JsonPrimitive(value.exists))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): CoreReadFileOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "readFile") {
                "core.readFile output payload kind must be 'readFile'"
            }
            // Back-compat: legacy envelope `{"kind":"readFile","outcome":"SUCCESS"}` has no
            // content/exists fields — decode as exists=true, content=null so REUSE of old
            // journals does not blow up. New writes always include the typed fields.
            val exists = obj["exists"]?.jsonPrimitive?.booleanOrNull ?: true
            // The `content` field can be (a) absent (legacy / exists=true with no read),
            // (b) the JSON literal `null` (exists=false with no read), or (c) a string.
            // We must distinguish (a)/(b) from (c) — `.jsonPrimitive.content` collapses
            // `JsonNull` to the literal string "null", which is a defect.
            val contentElement = obj["content"]
            val content: String? = when {
                contentElement == null -> null
                contentElement is kotlinx.serialization.json.JsonNull -> null
                else -> contentElement.jsonPrimitive.content
            }
            return CoreReadFileOutput(content = content, exists = exists)
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
            val result = ops.readFile(file = input.file, encoding = input.encoding)
            CoreReadFileOutput(content = result.content, exists = result.exists)
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
