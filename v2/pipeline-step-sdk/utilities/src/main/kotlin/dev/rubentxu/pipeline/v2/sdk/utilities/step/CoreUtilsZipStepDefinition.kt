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
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipOutput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ZipSources
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.streams.toList

/**
 * `core-utils.zip` OFFICIAL_PLUGIN Step (LFC-2E2 utilities, Slice 2 / S2.4).
 *
 * Jenkins reference: pipeline-utility-steps-plugin::ZipStep
 * (MIT, CloudBees). Behaviour summary in
 * `docs/v2/07-uat/S2_ZIP_JENKINS_REFERENCE.md`.
 *
 * Security:
 *  - Every source path is resolved against its declared base (workspace
 *    root for FromGlob/FromFiles, the explicit directory for FromDirectory).
 *  - Resolution refuses to escape the workspace root (CVE-2023-32981-style
 *    defence applied to the archive side: a malicious archive source cannot
 *    smuggle a path that escapes the base).
 *  - Symlinks under the source base are followed and archived as regular
 *    files (Jenkins' FilePath.zip behaviour). The behaviour is documented;
 *    this Step is NOT a symbolic-link-preserving archiver.
 */
class CoreUtilsZipStepDefinition : StepDefinition<ZipInput, ZipOutput> {

    override val contract: StepContract<ZipInput, ZipOutput> =
        StepContract(
            key = CoreUtilsZipKey.VALUE,
            descriptor = StepDescriptor(
                stepId = CoreUtilsZipKey.VALUE.value,
                name = "core-utils.zip",
                configRef = "",
                pluginId = "utilities",
                pluginVersion = "0.0.1-dev",  // overridden by manifest at registration
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.WRITES_WORKSPACE),
                replayPolicy = ReplayPolicy.NEVER,
                recoveryPolicy = RecoveryPolicy.None,
            ),
            inputCodec = CoreUtilsZipInputCodec,
            outputCodec = CoreUtilsZipOutputCodec,
            requiredCapabilities = setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY),
        )

    override val handler: StepHandler<ZipInput, ZipOutput> =
        StepHandler { input, ctx ->
            val workspaceRoot: Path = ctx.capabilities
                .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
                .workspaceRoot

            val target = CoreUtilsReadJsonStepDefinition.resolvePath(workspaceRoot, input.path)
            if (Files.exists(target) && !input.overwrite) {
                throw PluginStepException(
                    failure = PipelineFailure(
                        kind = FailureKind.USER,
                        message = "core-utils.zip: target already exists and overwrite=false: ${target}",
                    ),
                )
            }
            target.parent?.let { Files.createDirectories(it) }

            // Materialise the source list: each is an absolute path inside
            // the workspace root, plus the relative entry name to use in
            // the archive.
            val entries: List<Pair<Path, String>> = when (val s = input.sources) {
                is ZipSources.FromGlob -> materialiseGlob(workspaceRoot, s.glob)
                is ZipSources.FromDirectory -> materialiseDirectory(workspaceRoot, s.directory)
                is ZipSources.FromFiles -> materialiseFiles(workspaceRoot, s.paths)
            }

            // Stream the archive straight to disk. The digest and the
            // counter are updated on every byte; we never materialise the
            // whole archive in memory. CounterOutputStream is a thin
            // counting wrapper so we can report the final byte size
            // without a second pass over the file.
            val digest = MessageDigest.getInstance("SHA-256")
            val entryCountRef = intArrayOf(0)

            Files.newOutputStream(target).use { rawOut ->
                CounterOutputStream(rawOut).use { counter ->
                    DigestOutputStream(counter, digest).use { digestStream ->
                        BufferedOutputStream(digestStream).use { buffered ->
                            ZipOutputStream(buffered).use { zip ->
                                for ((absPath, entryName) in entries) {
                                    if (entryName.isBlank() || entryName.startsWith("/")) continue
                                    val entry = ZipEntry(entryName)
                                    zip.putNextEntry(entry)
                                    try {
                                        val bytes = absPath.readBytes()
                                        zip.write(bytes)
                                        zip.closeEntry()
                                        entryCountRef[0] += 1
                                    } catch (e: java.io.IOException) {
                                        throw PluginStepException(
                                            failure = PipelineFailure(
                                                kind = FailureKind.USER,
                                                message = "core-utils.zip: failed to read source '$absPath': ${e.message}",
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val byteSize = Files.size(target)
            ZipOutput(
                absolutePath = target.toString(),
                byteSize = byteSize,
                sha256Hex = digest.digest().joinToString("") { "%02x".format(it) },
                entryCount = entryCountRef[0],
            )
        }

    /**
     * Resolve a glob against [base] and return every regular file in the
     * match set. The list is filtered to entries that resolve inside [base].
     */
    private fun materialiseGlob(base: Path, glob: String): List<Pair<Path, String>> {
        // FindFiles semantics: the glob is rooted at base. We use NIO's
        // PathMatcher with the same Jenkins-compat fallback that
        // CoreUtilsFindFilesStepDefinition uses.
        val matcher = CoreUtilsFindFilesStepDefinition.buildMatcher(glob)
        val stream = Files.walk(base)
        return try {
            stream.use { s ->
                s.toList()
                    .filter { Files.isRegularFile(it) }
                    .map { abs ->
                        val rel = abs.relativeTo(base).toString().replace('\\', '/')
                        abs to rel
                    }
                    .filter { (_, rel) -> matcher.matches(java.nio.file.Path.of(rel)) }
                    .toList()
            }
        } catch (e: Exception) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.zip: glob walk failed for '$glob' under '$base': ${e.message}",
                ),
            )
        }
    }

    private fun materialiseDirectory(workspaceRoot: Path, directory: String): List<Pair<Path, String>> {
        val dir = CoreUtilsReadJsonStepDefinition.resolvePath(workspaceRoot, directory)
        if (!Files.isDirectory(dir)) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.zip: directory does not exist or is not a directory: $dir",
                ),
            )
        }
        val stream = Files.walk(dir)
        return try {
            stream.use { s ->
                s.toList()
                    .filter { Files.isRegularFile(it) }
                    .map { abs ->
                        val rel = abs.relativeTo(dir).toString().replace('\\', '/')
                        abs to rel
                    }
                    .toList()
            }
        } catch (e: Exception) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.zip: directory walk failed for '$dir': ${e.message}",
                ),
            )
        }
    }

    private fun materialiseFiles(workspaceRoot: Path, paths: List<String>): List<Pair<Path, String>> {
        // Each path must resolve to a regular file inside the workspace root.
        // Defence in depth (mirrors CVE-2023-32981 containment on the
        // archive-creation side): an absolute path that lives outside the
        // workspace is rejected before any byte is read.
        return paths.map { raw ->
            val abs = CoreUtilsReadJsonStepDefinition.resolvePath(workspaceRoot, raw)
            if (!abs.startsWith(workspaceRoot)) {
                throw PluginStepException(
                    failure = PipelineFailure(
                        kind = FailureKind.USER,
                        message = "core-utils.zip: source path escapes the workspace root: $raw",
                    ),
                )
            }
            if (!abs.isRegularFile()) {
                throw PluginStepException(
                    failure = PipelineFailure(
                        kind = FailureKind.USER,
                        message = "core-utils.zip: source path is not a regular file: $abs",
                    ),
                )
            }
            // Entry name: workspace-relative, forward-slash.
            val rel = runCatching { abs.relativeTo(workspaceRoot).toString() }
                .getOrDefault(abs.fileName.toString())
                .replace('\\', '/')
            abs to rel
        }
    }
}

/**
 * Wraps an underlying [java.io.OutputStream] and forwards every write
 * verbatim. Used as the outer sink of [DigestOutputStream] so the
 * ZipOutputStream chain ends up writing straight to the target file.
 */
internal class CounterOutputStream(private val sink: java.io.OutputStream) : java.io.OutputStream() {
    override fun write(b: Int) { sink.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len) }
    override fun flush() { sink.flush() }
    override fun close() { sink.close() }
}

/** Updates the digest with every byte, then forwards to the underlying stream. */
internal class DigestOutputStream(
    private val sink: java.io.OutputStream,
    private val digest: MessageDigest,
) : java.io.OutputStream() {
    override fun write(b: Int) {
        digest.update(b.toByte())
        sink.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        digest.update(b, off, len)
        sink.write(b, off, len)
    }

    override fun flush() { sink.flush() }
    override fun close() { sink.close() }
}
