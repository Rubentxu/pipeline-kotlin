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
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Input
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.Sha256Output
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * `core-utils.sha256` OFFICIAL_PLUGIN Step (utilities)
 * Capability discipline: declares `WORKSPACE_IDENTITY_CAPABILITY` and reads
 * the canonical workspace root from the typed capability seam.
 * Effects: `READ_ONLY` (read-only stream over the file).
 * The file is read in BOUNDED CHUNKS: it is never held whole, so an
 * artifact bigger than the heap of the controller still has a digest. The
 * size the step publishes is the size of the bytes it hashed.
 * ReplayPolicy: `MEMOIZED` (file content is deterministic; replay may reuse
 *   the cached output when the durable fingerprint matches).
 * RecoveryPolicy: `None`.
 * Algorithms supported: `SHA-256` (default) and `SHA-1`. We
 * deliberately do NOT accept any other algorithm name — a caller asking for
 * `MD5` is rejected at admission time with a typed USER-class failure rather
 * than silently substituted.
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
        requiredCapabilities = setOf<StepCapability>(EXECUTION_LOCATION_CAPABILITY),
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

        val location: ExecutionSite = ctx.capabilities.get(EXECUTION_LOCATION_CAPABILITY, ExecutionSite::class)
        val target: Path = WorkspacePathAnchors.currentDirectory(
            location = location,
            stepKey = "core-utils.sha256",
            userPath = input.path,
        )
        if (!target.exists() || !target.isRegularFile()) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.sha256: file not found: $target",
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

        // The file is read in BOUNDED CHUNKS and never held whole: this step
        // runs on the controller, and an artifact bigger than the heap of the
        // controller still has to have a digest.
        //
        // The SIZE is counted while the bytes go in, and not asked to the
        // filesystem: the size the step publishes is the size of what it
        // hashed, and a file that grows while it is read does not make the
        // two disagree.
        var byteSize = 0L
        val hex: String = try {
            Files.newInputStream(target).use { stream ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                    byteSize += read
                }
            }
            // The hex is written in ROOT: the digits of a locale that writes
            // them otherwise would be a digest that matches nothing.
            md.digest().joinToString(separator = "") { String.format(Locale.ROOT, "%02x", it) }
        } catch (e: java.io.IOException) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.INFRASTRUCTURE,
                    message = "core-utils.sha256: failed to read file '$target': ${e.message}",
                ),
            )
        }

        Sha256Output(
            hexDigest = hex,
            byteSize = byteSize,
            algorithm = algo,
        )
    }

    companion object {
        internal val ALLOWED_ALGORITHMS: Set<String> = setOf("SHA-256", "SHA-1")

        /**
         * How many bytes of the file are held at once.
         *
         * The same size the domain's streaming digest uses, so the two read a
         * file the same way and neither of them decides the size of the
         * window on its own.
         */
        private const val BUFFER_BYTES: Int = 8192
    }
}
