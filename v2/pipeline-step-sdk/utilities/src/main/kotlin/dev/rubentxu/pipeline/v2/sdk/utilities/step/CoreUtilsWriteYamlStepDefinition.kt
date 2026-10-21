package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
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
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation as ExecutionSite
import dev.rubentxu.pipeline.v2.sdk.WorkspacePathAnchors
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlDestination
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlPayload
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * `core-utils.writeYaml` OFFICIAL_PLUGIN Step.
 *
 * Guarantees:
 *  - `data XOR datas` and `file XOR returnText` are sealed-hierarchy invariants.
 *  - SnakeYAML 2.3 + DumperOptions in block flow style (`FlowStyle.BLOCK`,
 *    `splitLines=false`).
 *  - Output is the closed [WriteYamlOutput]; successful writes return
 *    file metadata OR text, never an `Any?` bag.
 *  - Workspace enforcement via the declared `WORKSPACE_IDENTITY_CAPABILITY`.
 *  - No recursive type validation is needed: the closed [YamlDocument] ADT
 *    makes an invalid shape impossible to construct, so there is nothing for a
 *    runtime check to reject.
 *
 * Effects: `WRITES_WORKSPACE` when destination is a file.
 * ReplayPolicy: `NEVER` — a successful durable write MUST NOT silently
 *   re-execute on replay.
 * RecoveryPolicy: `None`.
 */
class CoreUtilsWriteYamlStepDefinition : StepDefinition<WriteYamlInput, WriteYamlOutput> {

    override val contract = StepContract(
        key = CoreUtilsWriteYamlKey.VALUE,
        descriptor = StepDescriptor(
            stepId = CoreUtilsWriteYamlKey.VALUE.value,
            name = "core-utils.writeYaml",
            configRef = "",
            pluginId = "utilities",
            pluginVersion = "0.0.1-dev",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.NEVER,
            recoveryPolicy = RecoveryPolicy.None,
        ),
        inputCodec = CoreUtilsWriteYamlInputCodec,
        outputCodec = CoreUtilsWriteYamlOutputCodec,
        requiredCapabilities = setOf<StepCapability>(EXECUTION_LOCATION_CAPABILITY),
    )

    override val handler = StepHandler<WriteYamlInput, WriteYamlOutput> { input, ctx ->
        val yaml = Yaml(DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            splitLines = false
        })

        val text: String = when (val payload = input.payload) {
            is WriteYamlPayload.Single -> yaml.dump(YamlToJava.toJava(payload.value))
            is WriteYamlPayload.Multiple -> yaml.dumpAll(
                payload.documents.map { YamlToJava.toJava(it) }.iterator(),
            )
        }

        when (val dest = input.destination) {
            is WriteYamlDestination.ToFile -> {
                val location: ExecutionSite = ctx.capabilities.get(EXECUTION_LOCATION_CAPABILITY)
                val target: Path = WorkspacePathAnchors.currentDirectory(
                    location = location,
                    stepKey = "core-utils.writeYaml",
                    userPath = dest.path,
                )

                if (Files.exists(target) && !dest.overwrite) {
                    throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.USER,
                            message = "core-utils.writeYaml: target file already exists and overwrite=false: $target",
                        ),
                    )
                }
                val parent: Path = target.parent
                if (parent != null && !Files.exists(parent)) {
                    try {
                        Files.createDirectories(parent)
                    } catch (e: java.io.IOException) {
                        throw PluginStepException(
                            failure = PipelineFailure(
                                kind = FailureKind.INFRASTRUCTURE,
                                message = "core-utils.writeYaml: failed to create parent directory '$parent': ${e.message}",
                            ),
                        )
                    }
                }
                val bytes = text.toByteArray(Charsets.UTF_8)
                try {
                    Files.write(target, bytes)
                } catch (e: java.io.IOException) {
                    throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.INFRASTRUCTURE,
                            message = "core-utils.writeYaml: failed to write file '$target': ${e.message}",
                        ),
                    )
                }
                // Shared utility (B0). Same bytes, same lowercase hex.
                val hexDigest = Sha256.ofBytes(bytes)

                WriteYamlOutput(
                    wroteToFile = true,
                    text = null,
                    absolutePath = target.toString(),
                    byteSize = bytes.size.toLong(),
                    sha256Hex = hexDigest,
                )
            }
            WriteYamlDestination.ToText -> WriteYamlOutput(
                wroteToFile = false,
                text = text,
                absolutePath = null,
                byteSize = null,
                sha256Hex = null,
            )
        }
    }
}
