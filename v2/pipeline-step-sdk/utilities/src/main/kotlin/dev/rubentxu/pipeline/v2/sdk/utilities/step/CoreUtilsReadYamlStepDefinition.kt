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
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlSource
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * `core-utils.readYaml` OFFICIAL_PLUGIN Step (LFC-2E2 utilities, Slice 2 / S2.1).
 *
 * Reference: Jenkins `readYaml` (Pipeline Utility Steps Plugin, master).
 *   - docs:    https://www.jenkins.io/doc/pipeline/steps/pipeline-utility-steps/#readyaml-read-yaml-from-files-in-workspace-or-text
 *   - source:  ReadYamlStep.java / ReadYamlStep.Execution in
 *              `https://github.com/jenkinsci/pipeline-utility-steps-plugin/blob/master/src/main/java/org/jenkinsci/plugins/pipeline/utility/steps/conf/ReadYamlStep.java`
 *
 * Adaptation (full table in `docs/v2/07-uat/S2_READYAML_WRITEYAML_JENKINS_REFERENCE.md`):
 *  - file XOR text is encoded by the sealed [ReadYamlSource] hierarchy.
 *  - SnakeYAML 2.3 + [SafeConstructorOnlyOptions] + explicit tag allow-list.
 *  - Output is a typed [YamlDocument] ADT, not `Map<String, Any?>`. The
 *    safe constructor refuses to produce arbitrary Java classes; the closed
 *    ADT preserves that invariant across the durable boundary.
 *  - Workspace enforcement via the declared `WORKSPACE_IDENTITY_CAPABILITY`.
 *
 * Effects: `READ_ONLY` (parse-only; no writes).
 * ReplayPolicy: `MEMOIZED` (input is deterministic modulo file content).
 * RecoveryPolicy: `None` (no transient failure modes — the only failures are
 *   typed USER-class errors for missing files, unsafe tags, malformed input).
 */
class CoreUtilsReadYamlStepDefinition : StepDefinition<ReadYamlInput, ReadYamlOutput> {

    override val contract = StepContract(
        key = CoreUtilsReadYamlKey.VALUE,
        descriptor = StepDescriptor(
            stepId = CoreUtilsReadYamlKey.VALUE.value,
            name = "core-utils.readYaml",
            configRef = "",
            pluginId = "utilities",
            pluginVersion = "0.0.1-dev",  // overridden by manifest at registration
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
            recoveryPolicy = RecoveryPolicy.None,
        ),
        inputCodec = CoreUtilsReadYamlInputCodec,
        outputCodec = CoreUtilsReadYamlOutputCodec,
        requiredCapabilities = setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY),
    )

    override val handler = StepHandler<ReadYamlInput, ReadYamlOutput> { input, ctx ->
        val workspaceRoot: Path = ctx.capabilities
            .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
            .workspaceRoot

        // Source: file XOR text — already enforced by the sealed type, but we
        // also handle the on-disk / in-memory split here.
        var rawBytes: ByteArray
        var absolutePath: String? = null
        when (val source = input.source) {
            is ReadYamlSource.FromFile -> {
                val target = resolvePath(workspaceRoot, source.path)
                if (!target.exists() || !target.isRegularFile()) {
                    throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.USER,
                            message = "core-utils.readYaml: file not found: $target",
                        ),
                    )
                }
                rawBytes = try {
                    target.readBytes()
                } catch (e: java.io.IOException) {
                    throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.USER,
                            message = "core-utils.readYaml: failed to read file '$target': ${e.message}",
                        ),
                    )
                }
                absolutePath = target.toString()
            }
            is ReadYamlSource.FromText -> {
                rawBytes = source.text.toByteArray(Charsets.UTF_8)
            }
        }

        val rawText: String = try {
            rawBytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readYaml: source is not valid UTF-8: ${e.message}",
                ),
            )
        }

        // SnakeYAML parser with our safe-constructor options. Same approach as
        // Jenkins: pass the same LoaderOptions to BOTH the constructor and
        // the Yaml instance so the caps apply to both paths.
        val loaderOptions = SafeConstructorOnlyOptions.builderSafe()
        if (input.codePointLimit != null) {
            loaderOptions.codePointLimit = input.codePointLimit
        }
        if (input.maxAliasesForCollections != null) {
            loaderOptions.maxAliasesForCollections = input.maxAliasesForCollections
        }
        val yaml = Yaml(
            org.yaml.snakeyaml.constructor.SafeConstructor(loaderOptions),
            org.yaml.snakeyaml.representer.Representer(org.yaml.snakeyaml.DumperOptions()),
            org.yaml.snakeyaml.DumperOptions(),
            loaderOptions,
        )

        // loadAll returns one element per YAML document; an empty input returns an
        // empty iterable (we surface that as a typed failure rather than a
        // silent empty success).
        val documents: List<Any?> = try {
            val iterable: Iterable<Any?> = yaml.loadAll(rawText)
            iterable.toList()
        } catch (e: Exception) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readYaml: parse failed: ${e.message}",
                ),
            )
        }

        if (documents.isEmpty()) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readYaml: input produced no YAML documents",
                ),
            )
        }

        val typedDocs: List<YamlDocument> = documents.map { SnakeYamlAdapter.toDocument(it) }

        ReadYamlOutput(
            single = if (typedDocs.size == 1) typedDocs.single() else null,
            documents = if (typedDocs.size > 1) typedDocs else null,
            multipleDocuments = typedDocs.size > 1,
            byteSize = rawBytes.size.toLong(),
            absolutePath = absolutePath,
        )
    }

    companion object {
        /**
         * Resolve a workspace-relative path against the canonical workspace
         * root; absolute paths are honoured verbatim.
         */
        internal fun resolvePath(workspaceRoot: Path, rawPath: String): Path {
            val p = Path.of(rawPath)
            return if (p.isAbsolute) p else workspaceRoot.resolve(p)
        }
    }
}
