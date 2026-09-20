package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactHandle
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactQueryInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Registry-driven core Step for `core.artifact.query`
 * (E1.ecosystem-local-first cycle).
 *
 * Given a logical [ArtifactQueryInput.name], returns the
 * [ArtifactHandle] recorded by an earlier `core.archiveArtifacts`
 * with `name=...`. Pure read; no filesystem access; no event
 * emission; the only capability is the typed
 * [ArtifactIndexCapability].
 *
 * ## Effect classification (E1 cycle, no new Effect variants)

 * `core.artifact.query` is a pure read of the in-memory index; it
 * does NOT touch the filesystem (the durable record) and it does
 * NOT write anything. [Effect.READS_WORKSPACE] is the closest
 * variant (the index is a derived projection of the workspace's
 * artifacts retention directory). Recorded as the canonical
 * classification; will not change without an ADR.
 *
 * ## Replay policy

 * [ReplayPolicy.MEMOIZED] — the index is a derived projection; a
 * second invocation returns the cached handle. The handler does
 * NOT consult the filesystem, so replay is deterministic without
 * touching the durable record.
 *
 * ## Failure semantics

 * When the requested name is not in the index, the handler returns
 * a typed `INPUT_INVALID` failure carrying a closed
 * [ArtifactQueryFailureOutput] envelope. The Step itself does not
 * throw; `PluginStepException` would be re-classified as `ENGINE`
 * by the boundary (F5.2 / WU-LPR-FK precedent), losing the declared
 * kind.
 */
object CoreArtifactQueryStep {

    val KEY: PluginStepId = PluginStepId("core.artifact.query")

    /** Successful typed output: the handle found in the index. */
    data class ArtifactQuerySuccessOutput(
        val handle: ArtifactHandle,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Success
    }

    /** Typed failure output: name not found, or capability missing. */
    data class ArtifactQueryFailureOutput(
        val failureKind: FailureKind,
        val message: String,
    ) : TypedStepOutput {
        override val outcome: StepOutcome get() = StepOutcome.Failure(
            dev.rubentxu.pipeline.v2.domain.PipelineFailure(failureKind, message),
        )
    }

    private val inputCodec = object : StepCodec<ArtifactQueryInput> {
        override fun encode(value: ArtifactQueryInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("artifactQuery"))
                        put("name", JsonPrimitive(value.name))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): ArtifactQueryInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "artifactQuery") {
                "core.artifact.query payload kind must be 'artifactQuery'"
            }
            return ArtifactQueryInput(
                name = obj.getValue("name").jsonPrimitive.content,
            )
        }
    }

    private val outputCodec = object : StepCodec<TypedStepOutput> {
        override fun encode(value: TypedStepOutput): EncodedStepValue =
            when (value) {
                is ArtifactQuerySuccessOutput -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("artifactQuery"))
                            put("outcome", JsonPrimitive("OK"))
                            put("name", JsonPrimitive(value.handle.name))
                            put("fileCount", JsonPrimitive(value.handle.files.size))
                            put("aggregateSha256", JsonPrimitive(value.handle.aggregateSha256()))
                        },
                    ),
                )
                is ArtifactQueryFailureOutput -> EncodedStepValue(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("kind", JsonPrimitive("artifactQuery"))
                            put("outcome", JsonPrimitive("FAILED"))
                            put("failureKind", JsonPrimitive(value.failureKind.name))
                            put("message", JsonPrimitive(value.message))
                        },
                    ),
                )
                else -> throw IllegalArgumentException(
                    "core.artifact.query output codec cannot encode ${value::class.simpleName}",
                )
            }

        override fun decode(encoded: EncodedStepValue): TypedStepOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "artifactQuery") {
                "core.artifact.query output payload kind must be 'artifactQuery'"
            }
            val outcome = obj["outcome"]?.jsonPrimitive?.content
            if (outcome == "FAILED") {
                return ArtifactQueryFailureOutput(
                    failureKind = FailureKind.valueOf(obj.getValue("failureKind").jsonPrimitive.content),
                    message = obj.getValue("message").jsonPrimitive.content,
                )
            }
            // The output codec encode does NOT round-trip the full
            // ArtifactHandle.files (the typed carrier is the handler's
            // output; the encoded form is a fingerprint for the
            // journal + replay). The decoded form returns a synthetic
            // handle with zero files; this is only used for handler
            // self-tests, never by production consumers.
            val name = obj.getValue("name").jsonPrimitive.content
            return ArtifactQuerySuccessOutput(
                handle = ArtifactHandle(name = name, files = emptyList()),
            )
        }
    }

    /**
     * [RecoveryPolicy.None] — re-execution after a crash follows the
     * generic replay/reconcile path. The index is in-memory and
     * ephemeral, so a crash wipes it; this is acceptable for the E1
     * cycle (the durable record is the filesystem; the index is a
     * derived projection). A future cycle may introduce a journaled
     * index if cross-restart queries become a real requirement.
     */
    private val descriptor = StepDescriptor(
        stepId = "core.artifact.query",
        name = "artifactQuery",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
        recoveryPolicy = RecoveryPolicy.None,
    )

    /**
     * Capability-routed handler — zero infrastructure.
     *
     * Reads the index; fails closed with `INPUT_INVALID` when the
     * name is not recorded. Capability admission is fail-closed at
     * prepare-time by the registry boundary; the handler can call
     * `ctx.capabilities.get(...)` directly.
     */
    private val capabilityRoutedHandler: StepHandler<ArtifactQueryInput, TypedStepOutput> =
        StepHandler { input, ctx ->
            val index: ArtifactIndexCapability =
                ctx.capabilities.get(ARTIFACT_INDEX_CAPABILITY)
            val handle = index.query(input.name)
                ?: return@StepHandler ArtifactQueryFailureOutput(
                    failureKind = FailureKind.USER,
                    message = "Artifact '${input.name}' not found in index",
                )
            ArtifactQuerySuccessOutput(handle = handle)
        }

    val definition: StepDefinition<ArtifactQueryInput, TypedStepOutput> =
        object : StepDefinition<ArtifactQueryInput, TypedStepOutput> {
            override val contract: StepContract<ArtifactQueryInput, TypedStepOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(ARTIFACT_INDEX_CAPABILITY),
            )

            override val handler: StepHandler<ArtifactQueryInput, TypedStepOutput> =
                capabilityRoutedHandler
        }

    /** Registers through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
