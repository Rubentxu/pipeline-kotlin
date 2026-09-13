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
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Registry candidate for `core.archiveArtifacts` (LFC-2E1 S2-B10 / G1 registry seam proof).
 *
 * G1 registered this candidate WITHOUT changing `LEGACY_PLUGIN_IDS`. **G4 (2026-09-13) flipped
 * the authority**: `"core.archiveArtifacts"` was removed from `LEGACY_PLUGIN_IDS`, so
 * `StructuralFamilyResolver` now classifies the key `Registry` and this definition is the
 * production authority (wired through `CoreStepRegistryFactory`).
 *
 * The legacy decoder branch, the `CanonicalCoreStepMetadata["core.archiveArtifacts"]` row and
 * `CanonicalArchiveArtifactsNodeDispatcher` remain physically present but are UNREACHABLE in
 * production until G5 (LEGACY_REMOVED) deletes them. Counter: 2 ids / 3 metadata rows /
 * 3 dispatcher files.
 *
 * ## Capability discipline
 *
 * [ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY] is the ONLY declared capability. The handler
 * does NOT consume `EVENT_SINK_CAPABILITY` or stage identity directly — those are bound
 * by [ArchiveArtifactsOperationsAdapter]. The handler reaches the typed
 * [ArchiveArtifactsOperations] seam and never touches `Files.*`, `AntStyleGlob`,
 * [WorkspaceResolver], or the event sink itself.
 *
 * ## Effect classification (no new Effect variants)
 *
 * The legacy metadata row declares `Effect.READ_ONLY`, but archiveArtifacts copies files
 * OUT of the workspace into the artefacts retention directory. The closest existing
 * Effect variant is [Effect.WRITES_WORKSPACE] (the ADT has no ARTIFACTS_WRITE variant
 * and the memo justifies not inventing one at G1). The delta is recorded for the G2
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
            )
        }
    }

    /** Successful typed output; per-file evidence flows through the `ArtifactArchived` event. */
    data class ArchiveArtifactsOutput(
        val archivedCount: Int,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Success
    }

    /** Typed failure output; kind/message mirror the legacy dispatcher's SCRIPT failures. */
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
     * Byte-equivalent to the legacy `CanonicalCoreStepMetadata["core.archiveArtifacts"]`
     * row (`{Effect.READ_ONLY}, ReplayPolicy.MEMOIZED`) — recorded candidate delta:
     * effects upgraded to `{WRITES_WORKSPACE}` per the classification memo. Replay
     * policy unchanged.
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
     *
     * Delegates to [ArchiveArtifactsOperations.archive] via
     * [ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY] and maps the closed typed result to the
     * typed output ADT. The adapter is the single emitter of `ArtifactArchived` /
     * `ArtifactArchiveFailed`; the handler never touches the event sink.
     */
    private val capabilityRoutedHandler: StepHandler<ArchiveArtifactsInput, TypedStepOutput> =
        StepHandler { input, ctx ->
            val ops: ArchiveArtifactsOperations =
                ctx.capabilities.get(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY)
            when (val result = ops.archive(input)) {
                is ArchiveArtifactsSuccess -> ArchiveArtifactsOutput(archivedCount = result.files.size)
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
                requiredCapabilities = setOf(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY),
            )

            override val handler: StepHandler<ArchiveArtifactsInput, TypedStepOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
