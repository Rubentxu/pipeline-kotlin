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
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation as ExecutionSite
import dev.rubentxu.pipeline.v2.sdk.WorkspacePathAnchors
import dev.rubentxu.pipeline.v2.sdk.files.BoundedFileReader
import dev.rubentxu.pipeline.v2.sdk.files.BoundedRead
import dev.rubentxu.pipeline.v2.sdk.files.ReadQuota
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadYamlSource
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import org.yaml.snakeyaml.Yaml
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * `core-utils.readYaml` OFFICIAL_PLUGIN Step (utilities).
 * Reference: Jenkins `readYaml` (Pipeline Utility Steps Plugin, master).
 *   - docs:    https://www.jenkins.io/doc/pipeline/steps/pipeline-utility-steps/#readyaml-read-yaml-from-files-in-workspace-or-text
 *   - source:  ReadYamlStep.java / ReadYamlStep.Execution in
 *              `https://github.com/jenkinsci/pipeline-utility-steps-plugin/blob/master/src/main/java/org/jenkinsci/plugins/pipeline/utility/steps/conf/ReadYamlStep.java`
 * Adaptation:
 *  - file XOR text is encoded by the sealed [ReadYamlSource] hierarchy.
 *  - SnakeYAML 2.3 + [SafeConstructorOnlyOptions] + explicit tag allow-list.
 *  - Output is a typed [YamlDocument] ADT, not `Map<String, Any?>`. The
 *    safe constructor refuses to produce arbitrary Java classes; the closed
 *    ADT preserves that invariant across the durable boundary.
 *  - Workspace enforcement via the declared `WORKSPACE_IDENTITY_CAPABILITY`.
 * Effects: `READ_ONLY` (parse-only; no writes).
 * ReplayPolicy: `MEMOIZED` (input is deterministic modulo file content).
 * RecoveryPolicy: `None` (no transient failure modes — the only failures are
 *   typed USER-class errors for missing files, sources that are not UTF-8,
 *   files that do not fit the read quota, unsafe tags, malformed input).
 *
 * ## The quota of the read
 *
 * A document read from a FILE is read under [ReadQuota.MAX_FILE_BYTES]: the
 * limit the product reads every file under. A document that does not fit is
 * refused with the size it has and with the limit, and none of its content is
 * produced. The file is measured BEFORE it is read, so a document that does
 * not fit is never held whole.
 *
 * A document handed over as TEXT is not a file read: the text arrives in the
 * input of the Step, and the limit of the PARSE —eight mebibytes of code
 * points, the cap of the safe loader— is the one that governs it.
 *
 * @param readQuotaBytes the limit the read of a file runs under. It is the
 *   product's quota unless a caller names another one, and the tests name a
 *   small one so that a refusal needs no large file.
*/
class CoreUtilsReadYamlStepDefinition(
    private val readQuotaBytes: Long = ReadQuota.MAX_FILE_BYTES,
) : StepDefinition<ReadYamlInput, ReadYamlOutput> {

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
        requiredCapabilities = setOf<StepCapability>(EXECUTION_LOCATION_CAPABILITY),
    )

    override val handler = StepHandler<ReadYamlInput, ReadYamlOutput> { input, ctx ->
        val location: ExecutionSite = ctx.capabilities.get(EXECUTION_LOCATION_CAPABILITY, ExecutionSite::class)

        // Source: file XOR text — already enforced by the sealed type, but we
        // also handle the on-disk / in-memory split here.
        var rawBytes: ByteArray
        var absolutePath: String? = null
        when (val source = input.source) {
            is ReadYamlSource.FromFile -> {
                val target = WorkspacePathAnchors.currentDirectory(
                    location = location,
                    stepKey = "core-utils.readYaml",
                    userPath = source.path,
                )
                if (!target.exists() || !target.isRegularFile()) {
                    throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.USER,
                            message = "core-utils.readYaml: file not found: $target",
                        ),
                    )
                }
                // The read is held under the quota, and the file is measured
                // before it is read: a document that does not fit never becomes
                // whole, and the refusal names the limit and not the content.
                val read = try {
                    BoundedFileReader.read(target, readQuotaBytes)
                } catch (e: java.io.IOException) {
                    throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.USER,
                            message = "core-utils.readYaml: failed to read file '$target': ${e.message}",
                        ),
                    )
                }
                rawBytes = when (read) {
                    is BoundedRead.Fits -> read.bytes
                    is BoundedRead.TooLarge -> throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.USER,
                            message = "core-utils.readYaml: the file at $target holds at least " +
                                "${read.atLeastBytes} bytes, over the limit of ${read.limitBytes} bytes; " +
                                "the read was refused and no content was produced.",
                        ),
                    )
                }
                absolutePath = target.toString()
            }
            is ReadYamlSource.FromText -> {
                rawBytes = source.text.toByteArray(Charsets.UTF_8)
            }
        }

        val rawText: String = when (val decoding = StrictUtf8.decode(rawBytes)) {
            is Utf8Decoding.Text -> decoding.value
            is Utf8Decoding.Malformed -> throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readYaml: source is not valid UTF-8: " +
                        "the malformed sequence starts at the offset ${decoding.offset}",
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
}
