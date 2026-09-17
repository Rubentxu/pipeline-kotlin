package pipeline.utilities.filesystem

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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.stream.Stream

/**
 * LFC-2E2-EXPANSION U4 — filesystem traversal and file-timestamp support for
 * the first OFFICIAL_PLUGIN family.
 *
 * Unlike U1/U2/U3 (codec-shaped: read a file, decode it, project typed output),
 * U4 introduces a LIST-shaped output (`utilities.findFiles`) and a TIMESTAMP-shaped
 * effect (`utilities.touch`). This proves the architecture can host non-codec
 * families without bias toward file-decode operations.
 *
 *   - `utilities.findFiles` — walk a directory tree, return paths matching a glob.
 *     Output is a JSON array of strings (paths as strings; portable, no nio.Path
 *     type leaks into the public contract).
 *   - `utilities.touch` — create-or-update a file's last-modified timestamp.
 *     Optionally creates parent directories.
 *
 * Depends ONLY on the public SDK contracts (`pipeline-domain` +
 * `pipeline-scripting-api`) and JDK NIO (`java.nio.file`). No new external
 * dependency.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Typed failure ADT (LFC-2E2-EXPANSION U4 — filesystem hardening).
// ─────────────────────────────────────────────────────────────────────────────

sealed interface UtilitiesFilesystemError {
    /** The supplied root does not exist or is not a directory. */
    data class FilesystemNotFound(val path: String) : UtilitiesFilesystemError

    /** The supplied glob pattern is invalid. */
    data class FilesystemInvalidGlob(val pattern: String, val reason: String) : UtilitiesFilesystemError

    /** A generic I/O failure (permission denied, EIO, etc.). */
    data class FilesystemIoFailure(val path: String, val reason: String) : UtilitiesFilesystemError
}

class UtilitiesFilesystemException(val reason: UtilitiesFilesystemError) :
    RuntimeException("utilities.filesystem: ${reason::class.simpleName}: ${reason.describe()}")

private fun UtilitiesFilesystemError.describe(): String = when (this) {
    is UtilitiesFilesystemError.FilesystemNotFound -> "path not found: $path"
    is UtilitiesFilesystemError.FilesystemInvalidGlob -> "invalid glob '$pattern': $reason"
    is UtilitiesFilesystemError.FilesystemIoFailure -> "I/O failure at $path: $reason"
}

// ─────────────────────────────────────────────────────────────────────────────
// Capability port (typed narrow surface).
// ─────────────────────────────────────────────────────────────────────────────

interface UtilitiesFilesystemOperations {
    @Throws(UtilitiesFilesystemException::class)
    fun findFiles(root: String, glob: String, maxDepth: Int = Int.MAX_VALUE): FindFilesOutput

    @Throws(UtilitiesFilesystemException::class)
    fun touch(path: String, lastModifiedMillis: Long?, createDirs: Boolean): TouchOutput
}

// ─────────────────────────────────────────────────────────────────────────────
// findFiles — walk a directory tree, return paths matching a glob pattern.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class FindFilesInput(
    val root: String,
    val glob: String,
    val maxDepth: Int = 100,
)

@Serializable
data class FindFilesOutput(
    val root: String,
    val glob: String,
    val matches: List<String>,
    val matchCount: Int,
)

object FindFilesCodec : StepCodec<FindFilesInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun encode(value: FindFilesInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(FindFilesInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): FindFilesInput =
        json.decodeFromString(FindFilesInput.serializer(), encoded.value)
}

object FindFilesOutputCodec : StepCodec<FindFilesOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: FindFilesOutput): EncodedStepValue {
        // Encode the list of paths as a JSON string array (portable shape).
        val payload = json.encodeToString(
            ListSerializer(String.serializer()),
            value.matches,
        )
        // Wrap with the structured fields so the durable handler result can be inspected.
        val wrapped = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("root", kotlinx.serialization.json.JsonPrimitive(value.root))
                put("glob", kotlinx.serialization.json.JsonPrimitive(value.glob))
                put("matchCount", kotlinx.serialization.json.JsonPrimitive(value.matchCount))
                put("matches", kotlinx.serialization.json.Json.parseToJsonElement(payload))
            },
        )
        return EncodedStepValue(wrapped)
    }

    override fun decode(encoded: EncodedStepValue): FindFilesOutput {
        val obj = Json.parseToJsonElement(encoded.value).let { it as kotlinx.serialization.json.JsonObject }
        return FindFilesOutput(
            root = (obj["root"] as kotlinx.serialization.json.JsonPrimitive).content,
            glob = (obj["glob"] as kotlinx.serialization.json.JsonPrimitive).content,
            matches = (obj["matches"] as kotlinx.serialization.json.JsonArray).map {
                (it as kotlinx.serialization.json.JsonPrimitive).content
            },
            matchCount = (obj["matchCount"] as kotlinx.serialization.json.JsonPrimitive).content.toInt(),
        )
    }
}

object FindFilesStepDefinition : StepDefinition<FindFilesInput, FindFilesOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.findFiles")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "findFiles",
            configRef = "",
            pluginId = UtilitiesFilesystemContributor.COORDINATE,
            pluginVersion = UtilitiesFilesystemContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = FindFilesCodec,
        outputCodec = FindFilesOutputCodec,
        requiredCapabilities = setOf(UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY),
    )

    override val handler = StepHandler<FindFilesInput, FindFilesOutput> { input, ctx ->
        val ops: UtilitiesFilesystemOperations = ctx.capabilities.get(
            UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY,
        )
        ops.findFiles(input.root, input.glob, input.maxDepth)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// touch — create or update a file's last-modified timestamp.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class TouchInput(
    val path: String,
    val lastModifiedMillis: Long? = null,
    val createDirs: Boolean = false,
)

@Serializable
data class TouchOutput(
    val path: String,
    val created: Boolean,
    val lastModifiedMillis: Long,
)

object TouchCodec : StepCodec<TouchInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun encode(value: TouchInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(TouchInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): TouchInput =
        json.decodeFromString(TouchInput.serializer(), encoded.value)
}

object TouchOutputCodec : StepCodec<TouchOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: TouchOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(TouchOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): TouchOutput =
        json.decodeFromString(TouchOutput.serializer(), encoded.value)
}

object TouchStepDefinition : StepDefinition<TouchInput, TouchOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.touch")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "touch",
            configRef = "",
            pluginId = UtilitiesFilesystemContributor.COORDINATE,
            pluginVersion = UtilitiesFilesystemContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = TouchCodec,
        outputCodec = TouchOutputCodec,
        requiredCapabilities = setOf(UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY),
    )

    override val handler = StepHandler<TouchInput, TouchOutput> { input, ctx ->
        val ops: UtilitiesFilesystemOperations = ctx.capabilities.get(
            UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY,
        )
        ops.touch(input.path, input.lastModifiedMillis, input.createDirs)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Contributor + capability token.
// ─────────────────────────────────────────────────────────────────────────────

object UtilitiesFilesystemContributor {
    const val COORDINATE: String = pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE
    const val PLUGIN_VERSION: String = pipeline.utilities.json.UtilitiesJsonContributor.PLUGIN_VERSION

    /** Capability required by `findFiles` and `touch`. */
    val UTILITIES_FILESYSTEM_CAPABILITY: StepCapability =
        StepCapability("utilities.filesystem.operations")
}

// ─────────────────────────────────────────────────────────────────────────────
// Default FS-backed capability implementation.
// ─────────────────────────────────────────────────────────────────────────────

class DefaultUtilitiesFilesystemOperations : UtilitiesFilesystemOperations {

    override fun findFiles(root: String, glob: String, maxDepth: Int): FindFilesOutput {
        val rootPath: Path = try {
            Paths.get(root)
        } catch (e: Exception) {
            throw UtilitiesFilesystemException(
                UtilitiesFilesystemError.FilesystemNotFound(root),
            )
        }
        if (!Files.exists(rootPath)) {
            throw UtilitiesFilesystemException(
                UtilitiesFilesystemError.FilesystemNotFound(root),
            )
        }
        if (!Files.isDirectory(rootPath)) {
            throw UtilitiesFilesystemException(
                UtilitiesFilesystemError.FilesystemNotFound(root),
            )
        }

        // Java NIO PathMatcher's `glob:**/*.kt` pattern does NOT match root-level files
        // (the ** requires at least one directory separator before the leaf). To make
        // user-friendly globs like `**/*.kt` match uniformly, we synthesise a second
        // matcher from the same pattern without the `**/` prefix when applicable.
        val baseMatcher = try {
            rootPath.fileSystem.getPathMatcher("glob:$glob")
        } catch (e: java.util.regex.PatternSyntaxException) {
            throw UtilitiesFilesystemException(
                UtilitiesFilesystemError.FilesystemInvalidGlob(glob, e.message ?: e::class.simpleName.orEmpty()),
            )
        } catch (e: IllegalArgumentException) {
            throw UtilitiesFilesystemException(
                UtilitiesFilesystemError.FilesystemInvalidGlob(glob, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
        val leafMatcher: java.nio.file.PathMatcher? = if (glob.startsWith("**/")) {
            try {
                rootPath.fileSystem.getPathMatcher("glob:${glob.removePrefix("**/")}")
            } catch (_: Exception) {
                null
            }
        } else null

        val collected = mutableListOf<String>()
        try {
            Files.walk(rootPath, maxDepth.coerceAtLeast(0)).use { stream: Stream<Path> ->
                stream.forEach { p: Path ->
                    if (p == rootPath) return@forEach
                    val rel = try { rootPath.relativize(p) } catch (_: Exception) { p }
                    val matchedBase = try { baseMatcher.matches(rel) } catch (_: Exception) { false }
                    val matchedLeaf = leafMatcher?.let {
                        try { it.matches(p.fileName) } catch (_: Exception) { false }
                    } ?: false
                    if (matchedBase || matchedLeaf) {
                        collected.add(p.toString())
                    }
                }
            }
        } catch (e: java.io.IOException) {
            throw UtilitiesFilesystemException(
                UtilitiesFilesystemError.FilesystemIoFailure(root, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
        // Deterministic ordering: sort by path string so memoization is stable.
        collected.sort()
        return FindFilesOutput(
            root = root,
            glob = glob,
            matches = collected,
            matchCount = collected.size,
        )
    }

    override fun touch(path: String, lastModifiedMillis: Long?, createDirs: Boolean): TouchOutput {
        val file: Path = Paths.get(path)
        val existed = Files.exists(file)
        if (!existed && createDirs) {
            file.parent?.let { parent ->
                try {
                    Files.createDirectories(parent)
                } catch (e: java.io.IOException) {
                    throw UtilitiesFilesystemException(
                        UtilitiesFilesystemError.FilesystemIoFailure(
                            parent.toString(),
                            e.message ?: e::class.simpleName.orEmpty(),
                        ),
                    )
                }
            }
        }
        val ts: Long = lastModifiedMillis ?: System.currentTimeMillis()
        try {
            if (!existed) {
                Files.createFile(file)
            }
            Files.setLastModifiedTime(file, FileTime.fromMillis(ts))
        } catch (e: java.io.IOException) {
            throw UtilitiesFilesystemException(
                UtilitiesFilesystemError.FilesystemIoFailure(path, e.message ?: e::class.simpleName.orEmpty()),
            )
        } catch (e: java.nio.file.NoSuchFileException) {
            throw UtilitiesFilesystemException(
                UtilitiesFilesystemError.FilesystemNotFound(path),
            )
        }
        return TouchOutput(
            path = path,
            created = !existed,
            lastModifiedMillis = ts,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DSL extensions (typed Kotlin facades over the generic registryStep primitive).
// ─────────────────────────────────────────────────────────────────────────────

fun StageScope.findFiles(root: String, glob: String, maxDepth: Int = 100) =
    registryStep(
        stepKey = FindFilesStepDefinition.KEY,
        encodedInput = FindFilesCodec.encode(FindFilesInput(root, glob, maxDepth)),
    )

fun StageScope.touch(path: String, lastModifiedMillis: Long? = null, createDirs: Boolean = false) =
    registryStep(
        stepKey = TouchStepDefinition.KEY,
        encodedInput = TouchCodec.encode(TouchInput(path, lastModifiedMillis, createDirs)),
    )
