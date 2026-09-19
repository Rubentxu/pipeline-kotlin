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
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Input
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Output
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * `core-utils.sha256` OFFICIAL_PLUGIN Step (LFC-2E2 utilities).
 *
 * Capability discipline: declares `WORKSPACE_IDENTITY_CAPABILITY` and reads
 * the canonical workspace root from the typed capability seam.
 *
 * Effects: `READ_ONLY` (read-only stream over the file).
 * ReplayPolicy: `MEMOIZED` (file content is deterministic; replay may reuse
 *   the cached output when the durable fingerprint matches).
 * RecoveryPolicy: `None`.
 *
 * Algorithms supported in this slice: `SHA-256` (default) and `SHA-1`. We
 * deliberately do NOT accept any other algorithm name — a caller asking for
 * `MD5` is rejected at admission time with a typed USER-class failure rather
 * than silently substituted.
 *
 * Determinism: identical inputs (file content + algorithm) always produce
 * identical `hexDigest`.
 */
class CoreUtilsSha256StepDefinition : StepDefinition<Sha256Input, Sha256Output> {

    override val contract = StepContract(
        key = CoreUtilsSha256Key.VALUE,
        descriptor = StepDescriptor(
            stepId = CoreUtilsSha256Key.VALUE.value,
            name = "core-utils.sha256",
            configRef = "",
            pluginId = "utilities",
            pluginVersion = "0.0.1-dev",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
            recoveryPolicy = RecoveryPolicy.None,
        ),
        inputCodec = CoreUtilsSha256InputCodec,
        outputCodec = CoreUtilsSha256OutputCodec,
        requiredCapabilities = setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY),
    )

    override val handler = StepHandler<Sha256Input, Sha256Output> { input, ctx ->
        // Explicit-tolerance: the codec accepts arbitrary strings but the
        // handler refuses anything other than `SHA-256` (default) or `SHA-1`.
        // Anything else is a USER-class contract error, NOT a silent
        // substitution.
        val algo: String = input.algorithm
        if (algo !in ALLOWED_ALGORITHMS) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.sha256: unsupported algorithm '$algo'; allowed: $ALLOWED_ALGORITHMS",
                ),
            )
        }

        val capabilityWorkspaceRoot: Path = ctx.capabilities
            .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
            .workspaceRoot
        val target: Path = CoreUtilsReadJsonStepDefinition.resolvePath(
            workspaceRoot = capabilityWorkspaceRoot,
            rawPath = input.path,
        )
        if (!target.exists() || !target.isRegularFile()) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.sha256: file not found: $target",
                ),
            )
        }

        val bytes: ByteArray = try {
            Files.readAllBytes(target)
        } catch (e: java.io.IOException) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.INFRASTRUCTURE,
                    message = "core-utils.sha256: failed to read file '$target': ${e.message}",
                ),
            )
        }

        val md: MessageDigest = try {
            MessageDigest.getInstance(algo)
        } catch (e: NoSuchAlgorithmException) {
            // Defensive: should be unreachable after the explicit allow-list check.
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.INFRASTRUCTURE,
                    message = "core-utils.sha256: algorithm '$algo' not provided by JCE provider",
                ),
            )
        }
        val hex: String = md.digest(bytes).joinToString(separator = "") { "%02x".format(it) }

        Sha256Output(
            hexDigest = hex,
            byteSize = bytes.size.toLong(),
            algorithm = algo,
        )
    }

    companion object {
        internal val ALLOWED_ALGORITHMS: Set<String> = setOf("SHA-256", "SHA-1")
    }
}
