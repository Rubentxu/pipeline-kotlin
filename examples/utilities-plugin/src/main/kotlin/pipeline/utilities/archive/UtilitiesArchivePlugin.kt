package pipeline.utilities.archive

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.dsl.StageScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * LFC-2E2-EXPANSION U6 — ZIP archive support for the first OFFICIAL_PLUGIN
 * family.
 *
 *   - `utilities.zip` — bundle a directory tree into a ZIP archive.
 *     The source is a directory; entries are added recursively using
 *     `Files.walk`. The Output lists entry paths as strings.
 *
 *   - `utilities.unzip` — extract a ZIP archive into a destination directory.
 *     **MUST fail closed against Zip Slip** — entries whose normalised path
 *     resolves outside the destination root are rejected with a typed
 *     [UtilitiesArchiveError.UnzipPathTraversal] reason. Absolute paths in
 *     archive entries are also rejected.
 *
 * Depends ONLY on the public SDK contracts (`pipeline-domain` +
 * `pipeline-scripting-api`) and JDK NIO + java.util.zip. No new external
 * dependency.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Typed failure ADT (LFC-2E2-EXPANSION U6 — archive hardening).
// ─────────────────────────────────────────────────────────────────────────────

sealed interface UtilitiesArchiveError {
    /** The source (file or directory) does not exist or is not readable. */
    data class ArchiveNotFound(val path: String) : UtilitiesArchiveError

    /** Generic I/O failure (permission denied, EIO, malformed archive, etc.). */
    data class ArchiveIoFailure(val path: String, val reason: String) : UtilitiesArchiveError

    /** Reject (Zip Slip): archive entry resolves outside the destination root. */
    data class UnzipPathTraversal(val entry: String, val resolved: String) : UtilitiesArchiveError

    /** Reject: archive entry has an absolute path component. */
    data class UnzipAbsolutePath(val entry: String) : UtilitiesArchiveError
}

class UtilitiesArchiveException(val reason: UtilitiesArchiveError) :
    RuntimeException("utilities.archive: ${reason::class.simpleName}: ${reason.describe()}")

private fun UtilitiesArchiveError.describe(): String = when (this) {
    is UtilitiesArchiveError.ArchiveNotFound -> "path not found: $path"
    is UtilitiesArchiveError.ArchiveIoFailure -> "I/O failure at $path: $reason"
    is UtilitiesArchiveError.UnzipPathTraversal ->
        "Zip Slip rejected: entry '$entry' resolves outside destination root ('$resolved')"
    is UtilitiesArchiveError.UnzipAbsolutePath ->
        "absolute path in archive entry rejected: '$entry'"
}

// ─────────────────────────────────────────────────────────────────────────────
// Capability port (typed narrow surface).
// ─────────────────────────────────────────────────────────────────────────────

interface ArchiveOperations {
    @Throws(UtilitiesArchiveException::class)
    fun zip(sourceDir: String, targetZip: String, overwrite: Boolean): ZipOutput

    @Throws(UtilitiesArchiveException::class)
    fun unzip(sourceZip: String, targetDir: String, overwrite: Boolean): UnzipOutput
}

@Serializable
data class ZipOutput(
    val source: String,
    val target: String,
    val entryCount: Int,
    val entries: List<String>,
)

@Serializable
data class UnzipOutput(
    val source: String,
    val target: String,
    val entryCount: Int,
    val entries: List<String>,
)

// ─────────────────────────────────────────────────────────────────────────────
// zip — bundle a directory tree into a ZIP archive.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ZipInput(
    val sourceDir: String,
    val targetZip: String,
    val overwrite: Boolean = false,
)

object ZipCodec : StepCodec<ZipInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    override fun encode(value: ZipInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(ZipInput.serializer(), value))
    override fun decode(encoded: EncodedStepValue): ZipInput =
        json.decodeFromString(ZipInput.serializer(), encoded.value)
}

object ZipOutputCodec : StepCodec<ZipOutput> {
    private val json = Json { encodeDefaults = true }
    override fun encode(value: ZipOutput): EncodedStepValue {
        // Encode as JsonObject with the entries as a JSON array of strings.
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("source", JsonPrimitive(value.source))
            put("target", JsonPrimitive(value.target))
            put("entryCount", JsonPrimitive(value.entryCount))
            put("entries", JsonArray(value.entries.map { JsonPrimitive(it) }))
        }
        return EncodedStepValue(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj))
    }
    override fun decode(encoded: EncodedStepValue): ZipOutput {
        val obj = Json.parseToJsonElement(encoded.value) as kotlinx.serialization.json.JsonObject
        val entries = (obj["entries"] as JsonArray).map { (it as JsonPrimitive).content }
        return ZipOutput(
            source = (obj["source"] as JsonPrimitive).content,
            target = (obj["target"] as JsonPrimitive).content,
            entryCount = (obj["entryCount"] as JsonPrimitive).content.toInt(),
            entries = entries,
        )
    }
}

object ZipStepDefinition : StepDefinition<ZipInput, ZipOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.zip")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "zip",
            configRef = "",
            pluginId = UtilitiesArchiveContributor.COORDINATE,
            pluginVersion = UtilitiesArchiveContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = ZipCodec,
        outputCodec = ZipOutputCodec,
        requiredCapabilities = setOf(UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY),
    )

    override val handler = StepHandler<ZipInput, ZipOutput> { input, ctx ->
        val ops: ArchiveOperations = ctx.capabilities.get(
            UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY,
        )
        ops.zip(input.sourceDir, input.targetZip, input.overwrite)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// unzip — extract a ZIP archive into a destination directory. ZIP SLIP SAFE.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class UnzipInput(
    val sourceZip: String,
    val targetDir: String,
    val overwrite: Boolean = false,
)

object UnzipCodec : StepCodec<UnzipInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    override fun encode(value: UnzipInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(UnzipInput.serializer(), value))
    override fun decode(encoded: EncodedStepValue): UnzipInput =
        json.decodeFromString(UnzipInput.serializer(), encoded.value)
}

object UnzipOutputCodec : StepCodec<UnzipOutput> {
    private val json = Json { encodeDefaults = true }
    override fun encode(value: UnzipOutput): EncodedStepValue {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("source", JsonPrimitive(value.source))
            put("target", JsonPrimitive(value.target))
            put("entryCount", JsonPrimitive(value.entryCount))
            put("entries", JsonArray(value.entries.map { JsonPrimitive(it) }))
        }
        return EncodedStepValue(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj))
    }
    override fun decode(encoded: EncodedStepValue): UnzipOutput {
        val obj = Json.parseToJsonElement(encoded.value) as kotlinx.serialization.json.JsonObject
        val entries = (obj["entries"] as JsonArray).map { (it as JsonPrimitive).content }
        return UnzipOutput(
            source = (obj["source"] as JsonPrimitive).content,
            target = (obj["target"] as JsonPrimitive).content,
            entryCount = (obj["entryCount"] as JsonPrimitive).content.toInt(),
            entries = entries,
        )
    }
}

object UnzipStepDefinition : StepDefinition<UnzipInput, UnzipOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.unzip")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "unzip",
            configRef = "",
            pluginId = UtilitiesArchiveContributor.COORDINATE,
            pluginVersion = UtilitiesArchiveContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = UnzipCodec,
        outputCodec = UnzipOutputCodec,
        requiredCapabilities = setOf(UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY),
    )

    override val handler = StepHandler<UnzipInput, UnzipOutput> { input, ctx ->
        val ops: ArchiveOperations = ctx.capabilities.get(
            UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY,
        )
        ops.unzip(input.sourceZip, input.targetDir, input.overwrite)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Contributor + capability token.
// ─────────────────────────────────────────────────────────────────────────────

object UtilitiesArchiveContributor {
    const val COORDINATE: String = pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE
    const val PLUGIN_VERSION: String = pipeline.utilities.json.UtilitiesJsonContributor.PLUGIN_VERSION

    /** Capability required by `zip` and `unzip`. */
    val UTILITIES_ARCHIVE_CAPABILITY: StepCapability =
        StepCapability("utilities.archive.operations")
}

// ─────────────────────────────────────────────────────────────────────────────
// Default FS-backed capability implementation.
// ─────────────────────────────────────────────────────────────────────────────

class DefaultArchiveOperations : ArchiveOperations {

    override fun zip(sourceDir: String, targetZip: String, overwrite: Boolean): ZipOutput {
        val sourcePath: Path = Paths.get(sourceDir)
        val targetPath: Path = Paths.get(targetZip)

        if (!Files.exists(sourcePath)) {
            throw UtilitiesArchiveException(UtilitiesArchiveError.ArchiveNotFound(sourceDir))
        }
        if (!Files.isDirectory(sourcePath)) {
            throw UtilitiesArchiveException(UtilitiesArchiveError.ArchiveNotFound(sourceDir))
        }
        if (Files.exists(targetPath) && !overwrite) {
            throw UtilitiesArchiveException(
                UtilitiesArchiveError.ArchiveIoFailure(
                    targetZip,
                    "target exists and overwrite=false",
                ),
            )
        }

        // Deterministic ordering: sorted by relative path string. The reported
        // entries include directories as well as files; the writer skips them
        // when emitting the ZIP archive but the listing reflects the full walk.
        val collected = mutableListOf<String>()
        Files.walk(sourcePath).use { stream ->
            stream.filter { it != sourcePath }
                .sorted(Comparator.comparing { it.toString() })
                .forEach { file: Path ->
                    val rel = sourcePath.relativize(file).toString().replace('\\', '/')
                    collected.add(rel)
                }
        }
        // entryCount counts only file entries (the writer skips directories).
        val fileCount = collected.count { rel -> Files.isRegularFile(sourcePath.resolve(rel)) }

        targetPath.parent?.let { Files.createDirectories(it) }
        try {
            Files.newOutputStream(targetPath).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    for (entry in collected) {
                        val file = sourcePath.resolve(entry)
                        if (Files.isDirectory(file)) continue
                        zos.putNextEntry(ZipEntry(entry))
                        Files.copy(file, zos)
                        zos.closeEntry()
                    }
                }
            }
        } catch (e: java.io.IOException) {
            throw UtilitiesArchiveException(
                UtilitiesArchiveError.ArchiveIoFailure(targetZip, e.message ?: e::class.simpleName.orEmpty()),
            )
        }

        return ZipOutput(
            source = sourceDir,
            target = targetZip,
            entryCount = fileCount,
            entries = collected,
        )
    }

    override fun unzip(sourceZip: String, targetDir: String, overwrite: Boolean): UnzipOutput {
        val sourcePath: Path = Paths.get(sourceZip)
        val targetPath: Path = Paths.get(targetDir).toAbsolutePath().normalize()

        if (!Files.exists(sourcePath)) {
            throw UtilitiesArchiveException(UtilitiesArchiveError.ArchiveNotFound(sourceZip))
        }

        Files.createDirectories(targetPath)
        val collected = mutableListOf<String>()

        // Sanity-check the magic number before opening the stream. A text file
        // (or any non-ZIP input) does NOT trigger ZipException on nextEntry() —
        // the stream just returns null silently and the operation completes with
        // zero entries. We fail closed on non-PK inputs to prevent that silent
        // half-extraction.
        try {
            Files.newInputStream(sourcePath).use { fis ->
                val head = ByteArray(4)
                val read = fis.read(head)
                if (read < 2 ||
                    (head[0] != 0x50.toByte() || head[1] != 0x4B.toByte())
                ) {
                    throw UtilitiesArchiveException(
                        UtilitiesArchiveError.ArchiveIoFailure(
                            sourceZip,
                            "malformed archive: missing ZIP magic number (PK)",
                        ),
                    )
                }
            }
        } catch (e: UtilitiesArchiveException) {
            throw e
        } catch (e: java.io.IOException) {
            throw UtilitiesArchiveException(
                UtilitiesArchiveError.ArchiveIoFailure(sourceZip, e.message ?: e::class.simpleName.orEmpty()),
            )
        }

        // Wrap the entire zip stream in an outer try/catch so that malformed-archive
        // errors at any point (header parse, mid-stream corruption) surface as a typed
        // ArchiveIoFailure rather than escaping as a generic IOException.
        try {
            Files.newInputStream(sourcePath).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry: ZipEntry? = try {
                        zis.nextEntry
                    } catch (e: java.util.zip.ZipException) {
                        throw UtilitiesArchiveException(
                            UtilitiesArchiveError.ArchiveIoFailure(
                                sourceZip,
                                "malformed archive at first entry: " + (e.message ?: ""),
                            ),
                        )
                    }
                    while (entry != null) {
                        val name = entry.name
                        // 1. Reject absolute paths in archive entries (fail closed).
                        if (name.startsWith("/") || Paths.get(name).isAbsolute) {
                            throw UtilitiesArchiveException(
                                UtilitiesArchiveError.UnzipAbsolutePath(name),
                            )
                        }
                        val resolved = targetPath.resolve(name).toAbsolutePath().normalize()
                        // 2. Reject Zip Slip — entry must resolve under targetDir.
                        if (!resolved.startsWith(targetPath)) {
                            throw UtilitiesArchiveException(
                                UtilitiesArchiveError.UnzipPathTraversal(name, resolved.toString()),
                            )
                        }
                        if (entry.isDirectory) {
                            Files.createDirectories(resolved)
                        } else {
                            Files.createDirectories(resolved.parent ?: resolved)
                            if (Files.exists(resolved) && !overwrite) {
                                throw UtilitiesArchiveException(
                                    UtilitiesArchiveError.ArchiveIoFailure(
                                        resolved.toString(),
                                        "entry exists and overwrite=false",
                                    ),
                                )
                            }
                            val copyOption = StandardCopyOption.REPLACE_EXISTING
                            Files.copy(zis, resolved, copyOption)
                        }
                        collected.add(name)
                        entry = try {
                            zis.nextEntry
                        } catch (e: java.util.zip.ZipException) {
                            throw UtilitiesArchiveException(
                                UtilitiesArchiveError.ArchiveIoFailure(
                                    sourceZip,
                                    "malformed archive mid-stream: " + (e.message ?: ""),
                                ),
                            )
                        }
                    }
                }
            }
        } catch (e: UtilitiesArchiveException) {
            throw e
        } catch (e: java.util.zip.ZipException) {
            throw UtilitiesArchiveException(
                UtilitiesArchiveError.ArchiveIoFailure(sourceZip, "malformed archive: " + (e.message ?: "")),
            )
        } catch (e: java.io.IOException) {
            throw UtilitiesArchiveException(
                UtilitiesArchiveError.ArchiveIoFailure(sourceZip, e.message ?: e::class.simpleName.orEmpty()),
            )
        }

        return UnzipOutput(
            source = sourceZip,
            target = targetDir,
            entryCount = collected.size,
            entries = collected,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DSL extensions (typed Kotlin facades over the generic registryStep primitive).
// ─────────────────────────────────────────────────────────────────────────────

fun StageScope.zip(sourceDir: String, targetZip: String, overwrite: Boolean = false) =
    registryStep(
        stepKey = ZipStepDefinition.KEY,
        encodedInput = ZipCodec.encode(ZipInput(sourceDir, targetZip, overwrite)),
    )

fun StageScope.unzip(sourceZip: String, targetDir: String, overwrite: Boolean = false) =
    registryStep(
        stepKey = UnzipStepDefinition.KEY,
        encodedInput = UnzipCodec.encode(UnzipInput(sourceZip, targetDir, overwrite)),
    )
