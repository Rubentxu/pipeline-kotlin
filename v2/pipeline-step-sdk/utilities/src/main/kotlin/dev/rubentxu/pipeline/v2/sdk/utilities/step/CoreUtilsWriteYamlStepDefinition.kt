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
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlDestination
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.WriteYamlPayload
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * `core-utils.writeYaml` OFFICIAL_PLUGIN Step (LFC-2E2 utilities, Slice 2 / S2.2).
 *
 * Reference: Jenkins `writeYaml` (Pipeline Utility Steps Plugin, master).
 *   - docs:    https://www.jenkins.io/doc/pipeline/steps/pipeline-utility-steps/#writeyaml-write-a-yaml-from-file-or-text-content-and-persist-into-a-file
 *   - source:  WriteYamlStep.java in
 *              `https://github.com/jenkinsci/pipeline-utility-steps-plugin/blob/master/src/main/java/org/jenkinsci/plugins/pipeline/utility/steps/conf/WriteYamlStep.java`
 *
 * Adaptation (full table in `docs/v2/07-uat/S2_READYAML_WRITEYAML_JENKINS_REFERENCE.md`):
 *  - `data XOR datas` and `file XOR returnText` are sealed-hierarchy invariants.
 *  - SnakeYAML 2.3 + DumperOptions matching Jenkins (`FlowStyle.BLOCK`,
 *    `splitLines=false`).
 *  - Output is the closed [WriteYamlOutput]; successful writes return
 *    file metadata OR text, never an `Any?` bag.
 *  - Workspace enforcement via the declared `WORKSPACE_IDENTITY_CAPABILITY`.
 *  - Jenkins' recursive type validation (`isValidObjectType`) is replaced by
 *    the closed [YamlDocument] ADT: invalid shapes cannot be constructed in
 *    the first place, so the runtime check is unnecessary.
 *
 * Effects: `WRITES_WORKSPACE` when destination is a file.
 * ReplayPolicy: `NEVER` — a successful durable write MUST NOT silently
 *   re-execute on replay (E-EM-11 NEVER-1).
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
        requiredCapabilities = setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY),
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
                val workspaceRoot: Path = ctx.capabilities
                    .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
                    .workspaceRoot
                val target: Path = CoreUtilsReadJsonStepDefinition.resolvePath(
                    workspaceRoot = workspaceRoot,
                    rawPath = dest.path,
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
                val hexDigest = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }

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
