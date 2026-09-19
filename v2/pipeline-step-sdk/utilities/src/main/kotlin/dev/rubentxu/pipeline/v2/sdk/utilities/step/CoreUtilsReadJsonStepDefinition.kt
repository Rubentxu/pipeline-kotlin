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
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ReadJsonOutput
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * `core-utils.readJson` OFFICIAL_PLUGIN Step (LFC-2E2 utilities).
 *
 * Capability discipline: declares `WORKSPACE_IDENTITY_CAPABILITY` and reads the
 * canonical workspace root from the typed capability seam. The handler does
 * NOT consult `user.dir` or any system property at runtime.
 *
 * Effects: `READ_ONLY` (read-only file access).
 * ReplayPolicy: `MEMOIZED` (file content is deterministic; replay may reuse
 *   the cached output when the durable fingerprint matches).
 * RecoveryPolicy: `None` (no transient failure modes — the only failures are
 *   typed USER-class errors for missing files or invalid JSON).
 *
 * Determinism: identical inputs always produce identical outputs (modulo the
 * file content changing on disk).
 */
class CoreUtilsReadJsonStepDefinition : StepDefinition<ReadJsonInput, ReadJsonOutput> {

    override val contract = StepContract(
        key = CoreUtilsReadJsonKey.VALUE,
        descriptor = StepDescriptor(
            stepId = CoreUtilsReadJsonKey.VALUE.value,
            name = "core-utils.readJson",
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
        requiredCapabilities = setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY),
    )

    override val handler = StepHandler<ReadJsonInput, ReadJsonOutput> { input, ctx ->
        val capabilityWorkspaceRoot: Path = ctx.capabilities
            .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
            .workspaceRoot

        val target: Path = resolvePath(capabilityWorkspaceRoot, input.path)

        if (!target.exists() || !target.isRegularFile()) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readJson: file not found: $target",
                ),
            )
        }

        val rawBytes: ByteArray = try {
            Files.readAllBytes(target)
        } catch (e: java.io.IOException) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readJson: failed to read file '$target': ${e.message}",
                ),
            )
        }

        val rawText: String = try {
            rawBytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.readJson: file is not valid UTF-8: ${e.message}",
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
        // Lenient parser: accept pretty-printed or compact JSON without
        // requiring strict input. We surface invalid JSON as a typed failure,
        // not as a parser exception bubbling through the boundary.
        private val JSON_PARSER: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

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
