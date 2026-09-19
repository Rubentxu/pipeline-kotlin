package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.WORKSPACE_IDENTITY_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.WorkspaceIdentity
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteJsonOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * `core-utils.writeJson` OFFICIAL_PLUGIN Step (LFC-2E2 utilities).
 *
 * Capability discipline: declares `WORKSPACE_IDENTITY_CAPABILITY` and reads
 * the canonical workspace root from the typed capability seam.
 *
 * Effects: `WRITES_WORKSPACE` (durable side-effect on disk).
 * ReplayPolicy: `NEVER` — a successful durable write MUST NOT silently
 *   re-execute on replay (E-EM-11 NEVER-1). If a previous run recorded a
 *   successful write, replay aborts the re-execution with a typed failure
 *   rather than duplicating the effect.
 * RecoveryPolicy: `None`.
 *
 * Determinism: identical inputs always produce byte-identical output, and the
 * returned `sha256Hex` matches `sha256sum` of the file on disk.
 */
class CoreUtilsWriteJsonStepDefinition : StepDefinition<WriteJsonInput, WriteJsonOutput> {

    override val contract = StepContract(
        key = CoreUtilsWriteJsonKey.VALUE,
        descriptor = StepDescriptor(
            stepId = CoreUtilsWriteJsonKey.VALUE.value,
            name = "core-utils.writeJson",
            configRef = "",
            pluginId = "utilities",
            pluginVersion = "0.0.1-dev",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.NEVER,
            recoveryPolicy = RecoveryPolicy.None,
        ),
        inputCodec = CoreUtilsWriteJsonInputCodec,
        outputCodec = CoreUtilsWriteJsonOutputCodec,
        requiredCapabilities = setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY),
    )

    override val handler = StepHandler<WriteJsonInput, WriteJsonOutput> { input, ctx ->
        val capabilityWorkspaceRoot: Path = ctx.capabilities
            .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
            .workspaceRoot

        val target: Path = CoreUtilsReadJsonStepDefinition.resolvePath(
            workspaceRoot = capabilityWorkspaceRoot,
            rawPath = input.path,
        )

        // Validate the input shape BEFORE touching the filesystem. A
        // request that says "use rawText" but supplies neither value nor
        // rawText is a USER-class contract error.
        val serialized: String = when {
            input.useRawText -> input.rawText
                ?: throw PluginStepException(
                    failure = PipelineFailure(
                        kind = FailureKind.USER,
                        message = "core-utils.writeJson: useRawText=true requires rawText to be provided",
                    ),
                )

            else -> {
                val v: JsonElement = input.value
                    ?: throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.USER,
                            message = "core-utils.writeJson: missing 'value' (typed JsonElement) and useRawText is false",
                        ),
                    )
                if (input.prettyPrint) PRETTY_PRINTER.encodeToString(JsonElement.serializer(), v)
                else COMPACT_PRINTER.encodeToString(JsonElement.serializer(), v)
            }
        }

        // Create parent directories if needed.
        val parent: Path = target.parent
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent)
            } catch (e: java.io.IOException) {
                throw PluginStepException(
                    failure = PipelineFailure(
                        kind = FailureKind.INFRASTRUCTURE,
                        message = "core-utils.writeJson: failed to create parent directory '$parent': ${e.message}",
                    ),
                )
            }
        }

        val bytes: ByteArray = serialized.toByteArray(Charsets.UTF_8)
        try {
            Files.write(target, bytes)
        } catch (e: java.io.IOException) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.INFRASTRUCTURE,
                    message = "core-utils.writeJson: failed to write file '$target': ${e.message}",
                ),
            )
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hexDigest = digest.joinToString(separator = "") { "%02x".format(it) }

        WriteJsonOutput(
            absolutePath = target.toString(),
            byteSize = bytes.size.toLong(),
            sha256Hex = hexDigest,
            bytesWritten = bytes.size.toLong(),
        )
    }

    companion object {
        private val PRETTY_PRINTER: Json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }
        private val COMPACT_PRINTER: Json = Json {
            prettyPrint = false
            encodeDefaults = true
        }
    }
}
