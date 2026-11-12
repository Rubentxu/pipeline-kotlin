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
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonOutput
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * `core-utils.readJson` OFFICIAL_PLUGIN Step (utilities)
 * Capability discipline: declares `WORKSPACE_IDENTITY_CAPABILITY` and reads the
 * canonical workspace root from the typed capability seam. The handler does
 * NOT consult `user.dir` or any system property at runtime.
 * Effects: `READ_ONLY` (read-only file access).
 * ReplayPolicy: `MEMOIZED` (file content is deterministic; replay may reuse
 *   the cached output when the durable fingerprint matches).
 * RecoveryPolicy: `None` (no transient failure modes — the only failures are
 *   typed USER-class errors for missing files, files that are not UTF-8, files
 *   that do not fit the read quota, or invalid JSON).
 * Determinism: identical inputs always produce identical outputs (modulo the
 * file content changing on disk).
 *
 * ## The quota of the read
 *
 * The document is read under [ReadQuota.MAX_FILE_BYTES]: the limit the
 * product reads every file under. A document that does not fit is refused
 * with the size it has and with the limit, and none of its content is
 * produced. The file is measured BEFORE it is read, so a document that does
 * not fit is never held whole.
 *
 * @param readQuotaBytes the limit the read of the document runs under. It is
 *   the product's quota unless a caller names another one, and the tests name
 *   a small one so that a refusal needs no large file.
*/
class CoreUtilsReadJsonStepDefinition(
    private val readQuotaBytes: Long = ReadQuota.MAX_FILE_BYTES,
) : StepDefinition<ReadJsonInput, ReadJsonOutput> {

    override val contract = StepContract(
        key = CoreUtilsReadJsonKey.VALUE,
        descriptor = StepDescriptor(
            stepId = CoreUtilsReadJsonKey.VALUE.value,
            name = "core-utils.readJson",
            jenkinsSurface = "readJSON",
            jenkinsDivergence = "the Jenkins step is spelled readJSON; the DSL spells it readJson, the spelling of the DSL.",
            configRef = "",
            pluginId = "utilities",
            pluginVersion = "0.0.1-dev",  // overridden by manifest at registration
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
            recoveryPolicy = RecoveryPolicy.None,
        ),
        inputCodec = CoreUtilsReadJsonInputCodec,
        outputCodec = CoreUtilsReadJsonOutputCodec,
        requiredCapabilities = setOf<StepCapability>(EXECUTION_LOCATION_CAPABILITY),
    )

    override val handler = StepHandler<ReadJsonInput, ReadJsonOutput> { input, ctx ->
        val location: ExecutionSite = ctx.capabilities.get(EXECUTION_LOCATION_CAPABILITY, ExecutionSite::class)

        val target: Path = WorkspacePathAnchors.currentDirectory(
            location = location,
            stepKey = "core-utils.readJson",
            userPath = input.path,
        )

        if (!target.exists() || !target.isRegularFile()) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readJson: file not found: $target",
                ),
            )
        }

        // The read is held under the quota, and the file is measured before it
        // is read: a document that does not fit never becomes whole, and the
        // refusal names the limit and not the content.
        val read = try {
            BoundedFileReader.read(target, readQuotaBytes)
        } catch (e: java.io.IOException) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readJson: failed to read file '$target': ${e.message}",
                ),
            )
        }
        val rawBytes: ByteArray = when (read) {
            is BoundedRead.Fits -> read.bytes
            is BoundedRead.TooLarge -> throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readJson: the file at $target holds at least " +
                        "${read.atLeastBytes} bytes, over the limit of ${read.limitBytes} bytes; " +
                        "the read was refused and no content was produced.",
                ),
            )
        }

        val rawText: String = when (val decoding = StrictUtf8.decode(rawBytes)) {
            is Utf8Decoding.Text -> decoding.value
            is Utf8Decoding.Malformed -> throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readJson: file is not valid UTF-8: " +
                        "the malformed sequence starts at the offset ${decoding.offset}",
                ),
            )
        }

        val parsed: kotlinx.serialization.json.JsonElement? = if (input.returnRawText) {
            null
        } else {
            try {
                JSON_PARSER.parseToJsonElement(rawText)
            } catch (e: Exception) {
                throw PluginStepException(
                    failure = PipelineFailure(
                        kind = FailureKind.USER,
                        message = "core-utils.readJson: file content is not valid JSON: ${e.message}",
                    ),
                )
            }
        }

        ReadJsonOutput(
            rawText = rawText,
            parsed = parsed,
            byteSize = rawBytes.size.toLong(),
            absolutePath = target.toString(),
        )
    }

    companion object {
        // Strict parser: accept pretty-printed or compact JSON, but reject
        // anything that is not a well-formed JSON value. We surface invalid
        // JSON as a typed failure rather than a parser exception bubbling
        // through the boundary.
        private val JSON_PARSER: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
    }
}
