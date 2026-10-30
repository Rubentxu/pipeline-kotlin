package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
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
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The production definition of `core.archiveArtifacts`.
 * It is registered through the SAME mechanism an external plugin uses
 * ([CoreStepRegistryFactory]), and the registry is the only authority that resolves
 * the key. The legacy decoder branch, the core metadata table and the legacy
 * dispatcher that used to compete with it are gone: there is no second authority
 * left to keep in step.
 * ## Capability discipline
 * [ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY] is the ONLY declared capability. The handler
 * does NOT consume `EVENT_SINK_CAPABILITY` or stage identity directly — those are bound
 * by [ArchiveArtifactsOperationsAdapter]. The handler reaches the typed
 * [ArchiveArtifactsOperations] seam and never touches `Files.*`, `AntStyleGlob`,
 * [WorkspaceResolver], or the event sink itself.
 * ## Effect classification (no new Effect variants)
 * The legacy metadata row declares `Effect.READ_ONLY`, but archiveArtifacts copies files
 * OUT of the workspace into the artefacts retention directory. The closest existing
 * Effect variant is [Effect.WRITES_WORKSPACE] (the ADT has no ARTIFACTS_WRITE variant
 * And the memo justifies not inventing one at). The delta is recorded for the
 * differential contract freeze. Replay policy stays MEMOIZED (byte-equivalent with the
 * legacy row).
*/
object CoreArchiveArtifactsStep {

    val KEY: PluginStepId = PluginStepId("core.archiveArtifacts")

    /**
     * Canonical dsl-v1 input envelope, byte-identical to the legacy decode form emitted by
     * `DslCompiledPipelineCompiler.encodePayload` (`StepSpec.ArchiveArtifacts` branch):
     * `{"kind":"archiveArtifacts","artifacts":...,"allowEmptyArchive":...,"excludes":...,"fingerprint":...}`.
*/
    private val inputCodec = object : StepCodec<ArchiveArtifactsInput> {
        override fun encode(value: ArchiveArtifactsInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("archiveArtifacts"))
                        put("artifacts", JsonPrimitive(value.artifacts))
                        put("allowEmptyArchive", JsonPrimitive(value.allowEmptyArchive))
                        put("excludes", JsonPrimitive(value.excludes))
                        put("fingerprint", JsonPrimitive(value.fingerprint))
                        // Optional name. Only
                        // emitted when non-null to preserve byte-shape for
                        // legacy callers (backward-compat).
                        value.name?.let { put("name", JsonPrimitive(it)) }
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): ArchiveArtifactsInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "archiveArtifacts") {
                "core.archiveArtifacts payload kind must be 'archiveArtifacts'"
            }
            return ArchiveArtifactsInput(
                artifacts = obj.getValue("artifacts").jsonPrimitive.content,
                allowEmptyArchive = obj["allowEmptyArchive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                excludes = obj["excludes"]?.jsonPrimitive?.content ?: "",
                fingerprint = obj["fingerprint"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                name = obj["name"]?.jsonPrimitive?.content,
            )
        }
    }

    /** Successful typed output; per-file evidence flows through the `ArtifactArchived` event. */
    data class ArchiveArtifactsOutput(
        val archivedCount: Int,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Success
    }

    /** Typed failure output; kind/message describe the failure in the Step's own terms. */
    data class ArchiveArtifactsFailureOutput(
        val failureKind: FailureKind,
        val message: String,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Failure(
            dev.rubentxu.pipeline.v2.domain.PipelineFailure(failureKind, message),
        )
    }

    private val outputCodec = object : StepCodec<TypedStepOutput> {
        override fun encode(value: TypedStepOutput): EncodedStepValue =
            when (value) {
                is ArchiveArtifactsOutput -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("archiveArtifacts"))
                            put("archivedCount", JsonPrimitive(value.archivedCount))
                        },
                    ),
                )
                is ArchiveArtifactsFailureOutput -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("archiveArtifacts"))
                            put("outcome", JsonPrimitive("FAILED"))
                            put("failureKind", JsonPrimitive(value.failureKind.name))
                            put("message", JsonPrimitive(value.message))
                        },
                    ),
                )
                else -> throw IllegalArgumentException(
                    "core.archiveArtifacts output codec cannot encode ${value::class.simpleName}",
                )
            }

        override fun decode(encoded: EncodedStepValue): TypedStepOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "archiveArtifacts") {
                "core.archiveArtifacts output payload kind must be 'archiveArtifacts'"
            }
            val outcome = obj["outcome"]?.jsonPrimitive?.content
            if (outcome == "FAILED") {
                return ArchiveArtifactsFailureOutput(
                    failureKind = FailureKind.valueOf(obj.getValue("failureKind").jsonPrimitive.content),
                    message = obj.getValue("message").jsonPrimitive.content,
                )
            }
            return ArchiveArtifactsOutput(
                archivedCount = obj.getValue("archivedCount").jsonPrimitive.content.toInt(),
            )
        }
    }

    /**
     * Effects are `{WRITES_WORKSPACE}` — this Step writes into the workspace, so it
     * cannot claim `{READ_ONLY}`. Replay policy is `MEMOIZED`.
*/
    private val descriptor = StepDescriptor(
        stepId = "core.archiveArtifacts",
        name = "archiveArtifacts",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.WRITES_WORKSPACE),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    /**
     * Thin capability-routed handler — zero infrastructure.
     * Delegates to [ArchiveArtifactsOperations.archive] via
     * [ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY] and maps the closed typed result to the
     * typed output ADT. The adapter is the single emitter of `ArtifactArchived` /
     * `ArtifactArchiveFailed`; the handler never touches the event sink.
     * When `input.name != null`, the handler ALSO
     * resolves the [ARTIFACT_INDEX_CAPABILITY] (via `available()` since the
     * capability is OPTIONAL — see contract KDoc). When the capability is
     * absent, the handler returns a typed SCRIPT failure. When
     * `input.name == null`, the index is not consulted; legacy behaviour is
     * preserved verbatim.
*/
    private val capabilityRoutedHandler: StepHandler<ArchiveArtifactsInput, TypedStepOutput> =
        StepHandler { input, ctx ->
            val ops: ArchiveArtifactsOperations =
                ctx.capabilities.get(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY, ArchiveArtifactsOperations::class)
            when (val result = ops.archive(input)) {
                is ArchiveArtifactsSuccess -> {
                    if (input.name != null) {
                        // Index capability is OPTIONAL. Check availability
                        // before resolving to avoid the boundary's
                        // `get(key)` that fails on missing capabilities.
                        if (ARTIFACT_INDEX_CAPABILITY !in ctx.capabilities.available()) {
                            return@StepHandler ArchiveArtifactsFailureOutput(
                                failureKind = FailureKind.SCRIPT,
                                message = "archiveArtifacts 'name=${input.name}' requires " +
                                    "ARTIFACT_INDEX_CAPABILITY at composition root, but it is " +
                                    "not registered",
                            )
                        }
                        val index: dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability =
                            ctx.capabilities.get(ARTIFACT_INDEX_CAPABILITY, dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability::class)
                        val handle = dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactHandle(
                            name = input.name,
                            files = result.files.map {
                                dev.rubentxu.pipeline.v2.domain.step.artifact.ArchivedFileEntry(
                                    relPath = it.relPath,
                                    sha256 = it.sha256,
                                    sizeBytes = it.size,
                                    // ArchiveArtifactsOperations does NOT
                                    // expose absolutePath in the typed result
                                    // (it's a relative-path contract by design).
                                    // We synthesise a placeholder; the
                                    // filesystem remains the durable record
                                    // and the absolute path is recovered via
                                    // WorkspaceResolver at materialisation
                                    // time. Recorded as future-work item.
                                    absolutePath = "<archive://${it.relPath}>",
                                )
                            },
                        )
                        try {
                            index.record(handle)
                        } catch (e: dev.rubentxu.pipeline.v2.domain.step.artifact.DuplicateArtifactNameException) {
                            return@StepHandler ArchiveArtifactsFailureOutput(
                                failureKind = FailureKind.SCRIPT,
                                message = "archiveArtifacts name '${input.name}' is already recorded in this run",
                            )
                        }
                    }
                    ArchiveArtifactsOutput(archivedCount = result.files.size)
                }
                is ArchiveArtifactsFailed -> ArchiveArtifactsFailureOutput(
                    failureKind = result.failureKind,
                    message = result.message,
                )
            }
        }

    val definition: StepDefinition<ArchiveArtifactsInput, TypedStepOutput> =
        object : StepDefinition<ArchiveArtifactsInput, TypedStepOutput> {
            override val contract: StepContract<ArchiveArtifactsInput, TypedStepOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                // ARTIFACT_INDEX_CAPABILITY is NOT in the contract's
                // requiredCapabilities list because the same Step serves
                // both legacy (name=null) and named (name=...) modes, and
                // the contract is fixed at registration time. Instead, the
                // handler resolves the capability at runtime via
                // `ctx.capabilities.available()` and fails closed when
                // `input.name != null` but the index is absent. Legacy
                // callers (name=null) see no change.
                requiredCapabilities = setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY),
            )

            override val handler: StepHandler<ArchiveArtifactsInput, TypedStepOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(builder: StepRegistryBuilder) {
        builder.add(definition)
    }
}
