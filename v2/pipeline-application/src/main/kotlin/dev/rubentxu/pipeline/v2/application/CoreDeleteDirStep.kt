package dev.rubentxu.pipeline.v2.application
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
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
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.files.DeleteDirExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Typed input payload of `core.deleteDir` (S2-A7 / G1).
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
 * Registry candidate for `core.deleteDir` (S2-A7 / G1 registry seam proof).
 *
 * G1 registers this candidate WITHOUT changing `LEGACY_PLUGIN_IDS`, the legacy
 * decoder, the metadata row, or the legacy dispatcher:
 * `StructuralFamilyResolver`'s legacy-membership-wins rule keeps LegacyCore as
 * the production authority until the G3/G4 flip. Counters stay 6 / 6 / 6.
 *
 * Classification policy is **PATH_B verbatim** (G0 characterization):
 * - `DeleteDirExecutor` handles path resolution and deletion
 * - Workspace-root guard is enforced
 * - MEMOIZED replay via `.deleted` marker
 *
 * Capability separation:
 * - [WORKSPACE_RESOLVER_CAPABILITY] provides the canonical workspace resolver
 *   (stage workspace resolution and creation)
 * - [EVENT_SINK_CAPABILITY] publishes the durable `DirDeleted` observation
 *
 * Adjective checks: input codec preserves the legacy `{"kind":"deleteDir"}`
 * envelope; the handler is total (no exceptions as semantics); `DirDeleted`
 * fields mirror the legacy dispatcher byte-for-byte (uuid eventId, sequence 0L,
 * sha256 hex of path).
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

    private val capabilityRoutedHandler: StepHandler<DeleteDirInput, DeleteDirOutput> =
        StepHandler { input, ctx ->
            val stageIdentity: StageIdentity = ctx.capabilities.get(STAGE_IDENTITY_CAPABILITY)
            val resolver: WorkspaceResolverPort = ctx.capabilities.get(WORKSPACE_RESOLVER_CAPABILITY)
            val sink: EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)

            val stageWorkspace = resolver.resolve(stageIdentity.name, stageIdentity.index)
            resolver.ensureCreated(stageWorkspace)

            val executor = DeleteDirExecutor(
                workspaceResolver = { name, idx -> resolver.resolve(name, idx) },
            )

            val spec = dev.rubentxu.pipeline.v2.dsl.StepSpec.DeleteDir(path = input.path)
            val result = executor.execute(
                stageName = stageIdentity.name,
                stageIndex = stageIdentity.index,
                stepIndex = ctx.stepIndex,
                spec = spec,
            )

            sink.append(
                DirDeleted(
                    eventId = UUID.randomUUID().toString(),
                    runId = ctx.runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    path = result.path.toString(),
                    deletedCount = result.deletedCount,
                    sha256 = result.sha256,
                ),
            )

            DeleteDirOutput(
                path = result.path.toString(),
                deletedCount = result.deletedCount,
                sha256 = result.sha256,
            )
        }

    internal fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    val definition: StepDefinition<DeleteDirInput, DeleteDirOutput> =
        object : StepDefinition<DeleteDirInput, DeleteDirOutput> {
            override val contract: StepContract<DeleteDirInput, DeleteDirOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(
                    WORKSPACE_RESOLVER_CAPABILITY,
                    STAGE_IDENTITY_CAPABILITY,
                    EVENT_SINK_CAPABILITY,
                ) as Set<StepCapability>,
            )

            override val handler: StepHandler<DeleteDirInput, DeleteDirOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
