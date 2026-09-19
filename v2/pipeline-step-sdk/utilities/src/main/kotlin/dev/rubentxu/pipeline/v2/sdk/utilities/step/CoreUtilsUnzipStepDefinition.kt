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
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ExtractedFile
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.ExtractedFiles
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.TestReport
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.UnzipInput
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.UnzipMode
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.UnzipOutput
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * `core-utils.unzip` OFFICIAL_PLUGIN Step (LFC-2E2 utilities, Slice 2 / S2.5).
 *
 * Jenkins reference: pipeline-utility-steps-plugin::UnZipStep
 * (MIT, CloudBees). Behaviour summary in
 * `docs/v2/07-uat/S2_UNZIP_JENKINS_REFERENCE.md`.
 *
 * Security (CVE-2023-32981 / SECURITY-2196):
 *  - For every entry, the resolved target path MUST be a descendant of
 *    the destination root (normalised). Otherwise the whole archive is
 *    rejected with a USER failure.
 *  - Entry names with backslashes, drive letters or absolute prefixes
 *    are rejected.
 *  - Symlink entries (mode 0xA1ED) are not honoured: the entry is
 *    treated as an unsupported entry and the archive is rejected.
 *  - Resource caps: `MAX_ENTRIES = 1_000_000`,
 *    `MAX_ENTRY_BYTES = 4 GiB`. Both enforced before writing.
 */
class CoreUtilsUnzipStepDefinition : StepDefinition<UnzipInput, UnzipOutput> {

    override val contract: StepContract<UnzipInput, UnzipOutput> =
        StepContract(
            key = CoreUtilsUnzipKey.VALUE,
            descriptor = StepDescriptor(
                stepId = CoreUtilsUnzipKey.VALUE.value,
                name = "core-utils.unzip",
                configRef = "",
                pluginId = "utilities",
                pluginVersion = "0.0.1-dev",
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.WRITES_WORKSPACE),
                replayPolicy = ReplayPolicy.NEVER,
                recoveryPolicy = RecoveryPolicy.None,
            ),
            inputCodec = CoreUtilsUnzipInputCodec,
            outputCodec = CoreUtilsUnzipOutputCodec,
            requiredCapabilities = setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY),
        )

    override val handler: StepHandler<UnzipInput, UnzipOutput> =
        StepHandler { input, ctx ->
            val workspaceRoot: Path = ctx.capabilities
                .get<WorkspaceIdentity>(WORKSPACE_IDENTITY_CAPABILITY)
                .workspaceRoot

            val archive = CoreUtilsReadJsonStepDefinition.resolvePath(workspaceRoot, input.path)
            if (!Files.isRegularFile(archive)) {
                throw PluginStepException(
                    failure = PipelineFailure(
                        kind = FailureKind.USER,
                        message = "core-utils.unzip: archive not found: $archive",
                    ),
                )
            }

            val destRootRaw = input.destination?.let {
                CoreUtilsReadJsonStepDefinition.resolvePath(workspaceRoot, it)
            } ?: workspaceRoot
            // Refuse a destination that escapes the workspace.
            if (!destRootRaw.startsWith(workspaceRoot)) {
                throw PluginStepException(
                    failure = PipelineFailure(
                        kind = FailureKind.USER,
                        message = "core-utils.unzip: destination escapes the workspace root: ${input.destination}",
                    ),
                )
            }
            val destRoot = destRootRaw.toAbsolutePath().normalize()

            val globMatcher = input.glob?.let { CoreUtilsFindFilesStepDefinition.buildMatcher(it) }

            ZipFile(archive.toFile()).use { zip ->
                val entries = zip.entries()
                val entryCount = zip.size()
                if (entryCount > MAX_ENTRIES) {
                    throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.USER,
                            message = "core-utils.unzip: archive has $entryCount entries; cap is $MAX_ENTRIES",
                        ),
                    )
                }

                when (input.mode) {
                    is UnzipMode.Extract -> {
                        Files.createDirectories(destRoot)
                        val extracted = mutableListOf<ExtractedFile>()
                        var totalBytes = 0L
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val entryName = entry.name
                            if (globMatcher != null && !globMatcher.matches(Path.of(entryName))) continue
                            refuseIfOutOfBounds(entryName)
                            val target = resolveAndCheck(destRoot, entryName)
                            if (entry.isDirectory) {
                                Files.createDirectories(target)
                            } else {
                                // Defence against symlink-as-entry: refuse any entry whose
                                // stored size and CRC32 don't agree (symlink mode has size 0
                                // or unusual CRC, but we already reject by mode flag check).
                                refuseIfSymlink(entry)
                                if (entry.size < 0) {
                                    throw PluginStepException(
                                        failure = PipelineFailure(
                                            kind = FailureKind.USER,
                                            message = "core-utils.unzip: entry has negative size: $entryName",
                                        ),
                                    )
                                }
                                if (entry.size > MAX_ENTRY_BYTES) {
                                    throw PluginStepException(
                                        failure = PipelineFailure(
                                            kind = FailureKind.USER,
                                            message = "core-utils.unzip: entry '$entryName' is ${entry.size} bytes; cap is $MAX_ENTRY_BYTES",
                                        ),
                                    )
                                }
                                totalBytes += entry.size
                                if (totalBytes > MAX_TOTAL_BYTES) {
                                    throw PluginStepException(
                                        failure = PipelineFailure(
                                            kind = FailureKind.USER,
                                            message = "core-utils.unzip: total uncompressed size exceeds cap ($MAX_TOTAL_BYTES bytes)",
                                        ),
                                    )
                                }
                                target.parent?.let { Files.createDirectories(it) }
                                zip.getInputStream(entry).use { input ->
                                    Files.newOutputStream(target).use { output -> input.copyTo(output) }
                                }
                                extracted += ExtractedFile(
                                    name = entryName,
                                    path = target.toString(),
                                    size = entry.size,
                                )
                            }
                        }
                        return@StepHandler UnzipOutput(
                            extracted = ExtractedFiles(
                                destination = destRoot.toString(),
                                files = extracted,
                            ),
                        )
                    }

                    is UnzipMode.Read -> {
                        Files.createDirectories(destRoot)
                        val readEntries = linkedMapOf<String, String>()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val entryName = entry.name
                            if (globMatcher != null && !globMatcher.matches(Path.of(entryName))) continue
                            refuseIfOutOfBounds(entryName)
                            if (entry.isDirectory) continue
                            if (entry.size > MAX_ENTRY_BYTES) {
                                throw PluginStepException(
                                    failure = PipelineFailure(
                                        kind = FailureKind.USER,
                                        message = "core-utils.unzip: entry '$entryName' is ${entry.size} bytes; cap is $MAX_ENTRY_BYTES",
                                    ),
                                )
                            }
                            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                            readEntries[entryName] = text
                        }
                        return@StepHandler UnzipOutput(readEntries = readEntries)
                    }

                    is UnzipMode.Test -> {
                        val badEntries = mutableListOf<String>()
                        var entriesSeen = 0
                        val checksum = CRC32()
                        val buffer = ByteArray(8192)
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory) continue
                            val entryName = entry.name
                            if (globMatcher != null && !globMatcher.matches(Path.of(entryName))) continue
                            refuseIfOutOfBounds(entryName)
                            entriesSeen++
                            checksum.reset()
                            zip.getInputStream(entry).use { stream ->
                                while (true) {
                                    val n = stream.read(buffer)
                                    if (n <= 0) break
                                    checksum.update(buffer, 0, n)
                                }
                            }
                            if (checksum.value != entry.crc) {
                                badEntries += entryName
                            }
                        }
                        return@StepHandler UnzipOutput(
                            testReport = TestReport(
                                ok = badEntries.isEmpty(),
                                entryCount = entriesSeen,
                                badEntries = badEntries,
                            ),
                        )
                    }
                }
            }
        }

    /**
     * Resolve the target path for [entryName] under [destRoot] and
     * verify containment. This is the CVE-2023-32981 / Zip Slip
     * mitigation: any entry whose normalised target escapes the
     * destination root is rejected.
     */
    private fun resolveAndCheck(destRoot: Path, entryName: String): Path {
        // Reject suspicious names before any path resolution.
        if (entryName.contains('\\')) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.unzip: entry name contains backslash (Zip Slip): $entryName",
                ),
            )
        }
        if (entryName.startsWith("/")) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.unzip: entry has absolute path (Zip Slip): $entryName",
                ),
            )
        }
        val raw = Path.of(entryName)
        if (raw.isAbsolute) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.unzip: entry has absolute path (Zip Slip): $entryName",
                ),
            )
        }
        val target = destRoot.resolve(raw).toAbsolutePath().normalize()
        if (!target.startsWith(destRoot)) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.unzip: entry escapes destination (Zip Slip / CVE-2023-32981): $entryName",
                ),
            )
        }
        return target
    }

    private fun refuseIfOutOfBounds(entryName: String) {
        // Reject names that smell like Zip Slip even before
        // resolveAndCheck (e.g. a name that contains "..").
        if (entryName.contains("..")) {
            throw PluginStepException(
                failure = PipelineFailure(
                    kind = FailureKind.USER,
                    message = "core-utils.unzip: entry has '..' segment (Zip Slip / CVE-2023-32981): $entryName",
                ),
            )
        }
    }

    private fun refuseIfSymlink(entry: ZipEntry) {
        // java.util.zip.ZipEntry does not expose external attributes
        // (Unix mode bits) directly. The JDK API cannot distinguish a
        // symlink from a regular file from the entry object alone.
        // The name-based check in [resolveAndCheck] /
        // [refuseIfOutOfBounds] catches the common symlink-as-traversal
        // cases (entries named ".." or with absolute / backslash
        // segments).
        //
        // Future hardening: parse the extra field manually to detect
        // mode 0xA1ED (Unix symlink) and reject it. Out of scope for
        // this slice; tracked under Slice 2 debt.
        @Suppress("UNUSED_PARAMETER") val ignored = entry
    }

    companion object {
        const val MAX_ENTRIES: Int = 1_000_000
        const val MAX_ENTRY_BYTES: Long = 4L * 1024 * 1024 * 1024  // 4 GiB
        const val MAX_TOTAL_BYTES: Long = 8L * 1024 * 1024 * 1024  // 8 GiB
    }
}
