package pipeline.utilities.yaml

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
import kotlinx.serialization.json.JsonElement
import org.yaml.snakeyaml.Yaml

/**
 * LFC-2E2-EXPANSION U2 — YAML support for the first OFFICIAL_PLUGIN family.
 *
 * Mirrors the U1 JSON hardening pattern: a typed [UtilitiesYamlError] sealed ADT,
 * a typed [UtilitiesYamlException] carrier, and a capability port
 * [UtilitiesYamlOperations] that implementations MUST throw on every failure path.
 *
 * Adds two Step families to the existing `pipeline.utilities.json@1.0.0`
 * plugin coordinate (kept for continuity; the coordinate is the plugin identity,
 * not the family surface):
 *
 *   - `utilities.readYaml` — read a file, decode YAML, return a typed JSON value.
 *   - `utilities.writeYaml` — write a JSON value to a file as YAML.
 *
 * Depends ONLY on public SDK contracts (pipeline-domain + pipeline-scripting-api),
 * the plugin's existing kotlinx-serialization-json transitive, and
 * SnakeYAML for the YAML codec. No application/runtime internal package is imported.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Typed failure ADT (LFC-2E2-EXPANSION U2 — YAML hardening).
// ─────────────────────────────────────────────────────────────────────────────

sealed interface UtilitiesYamlError {
    /** The target file does not exist or is not readable. */
    data class YamlNotFound(val path: String) : UtilitiesYamlError

    /** The target file is reachable but its content is not valid YAML. */
    data class YamlParseFailure(val path: String, val reason: String) : UtilitiesYamlError

    /** A filesystem-level I/O failure other than NotFound/Parse (permission denied, EIO, etc.). */
    data class YamlIoFailure(val path: String, val reason: String) : UtilitiesYamlError
}

class UtilitiesYamlException(val reason: UtilitiesYamlError) :
    RuntimeException("utilities.yaml: ${reason::class.simpleName}: ${reason.describe()}")

private fun UtilitiesYamlError.describe(): String = when (this) {
    is UtilitiesYamlError.YamlNotFound -> "file not found: $path"
    is UtilitiesYamlError.YamlParseFailure -> "parse failure at $path: $reason"
    is UtilitiesYamlError.YamlIoFailure -> "I/O failure at $path: $reason"
}

// ─────────────────────────────────────────────────────────────────────────────
// Capability port (typed narrow surface).
// ─────────────────────────────────────────────────────────────────────────────

interface UtilitiesYamlOperations {
    @Throws(UtilitiesYamlException::class)
    fun readYaml(path: String): ReadYamlOutput

    @Throws(UtilitiesYamlException::class)
    fun writeYaml(path: String, value: JsonElement): WriteYamlOutput
}

// ─────────────────────────────────────────────────────────────────────────────
// readYaml — read a file and decode its content as a JSON value.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ReadYamlInput(val path: String)

@Serializable
data class ReadYamlOutput(
    val path: String,
    val bytes: Long,
    /** The decoded YAML root, projected through JsonElement so callers can treat YAML and JSON identically. */
    val value: JsonElement,
)

object ReadYamlCodec : StepCodec<ReadYamlInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun encode(value: ReadYamlInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(ReadYamlInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): ReadYamlInput =
        json.decodeFromString(ReadYamlInput.serializer(), encoded.value)
}

object ReadYamlOutputCodec : StepCodec<ReadYamlOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: ReadYamlOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(ReadYamlOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): ReadYamlOutput =
        json.decodeFromString(ReadYamlOutput.serializer(), encoded.value)
}

object ReadYamlStepDefinition : StepDefinition<ReadYamlInput, ReadYamlOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.readYaml")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "readYaml",
            configRef = "",
            pluginId = UtilitiesYamlContributor.COORDINATE,
            pluginVersion = UtilitiesYamlContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = ReadYamlCodec,
        outputCodec = ReadYamlOutputCodec,
        requiredCapabilities = setOf(UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY),
    )

    override val handler = StepHandler<ReadYamlInput, ReadYamlOutput> { input, ctx ->
        val ops: UtilitiesYamlOperations = ctx.capabilities.get(
            UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY,
        )
        ops.readYaml(input.path)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// writeYaml — write a JSON value to a file as YAML.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class WriteYamlInput(
    val path: String,
    val value: JsonElement,
)

@Serializable
data class WriteYamlOutput(
    val path: String,
    val bytes: Long,
)

object WriteYamlCodec : StepCodec<WriteYamlInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun encode(value: WriteYamlInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(WriteYamlInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): WriteYamlInput =
        json.decodeFromString(WriteYamlInput.serializer(), encoded.value)
}

object WriteYamlOutputCodec : StepCodec<WriteYamlOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: WriteYamlOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(WriteYamlOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): WriteYamlOutput =
        json.decodeFromString(WriteYamlOutput.serializer(), encoded.value)
}

object WriteYamlStepDefinition : StepDefinition<WriteYamlInput, WriteYamlOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.writeYaml")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "writeYaml",
            configRef = "",
            pluginId = UtilitiesYamlContributor.COORDINATE,
            pluginVersion = UtilitiesYamlContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = WriteYamlCodec,
        outputCodec = WriteYamlOutputCodec,
        requiredCapabilities = setOf(UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY),
    )

    override val handler = StepHandler<WriteYamlInput, WriteYamlOutput> { input, ctx ->
        val ops: UtilitiesYamlOperations = ctx.capabilities.get(
            UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY,
        )
        ops.writeYaml(input.path, input.value)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Contributor + capability token.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The contributor ships the two YAML Step families alongside the existing
 * three JSON ones. NOTE: this reuses the existing
 * `UtilitiesJsonContributor.COORDINATE` (`pipeline.utilities.json`) — the
 * coordinate is the plugin identity, not the family surface. Adding new
 * families does not require a new coordinate.
 */
object UtilitiesYamlContributor {
    const val COORDINATE: String = pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE
    const val PLUGIN_VERSION: String = pipeline.utilities.json.UtilitiesJsonContributor.PLUGIN_VERSION

    /** Capability required by `readYaml` and `writeYaml`. */
    val UTILITIES_YAML_CAPABILITY: StepCapability = StepCapability("utilities.yaml.operations")
}

// ─────────────────────────────────────────────────────────────────────────────
// Default FS-backed capability implementation.
// ─────────────────────────────────────────────────────────────────────────────

class DefaultUtilitiesYamlOperations : UtilitiesYamlOperations {
    override fun readYaml(path: String): ReadYamlOutput {
        val file = java.io.File(path)
        if (!file.exists()) {
            throw UtilitiesYamlException(UtilitiesYamlError.YamlNotFound(path))
        }
        val bytes = try {
            file.readBytes()
        } catch (e: java.io.IOException) {
            throw UtilitiesYamlException(UtilitiesYamlError.YamlIoFailure(path, e.message ?: e::class.simpleName.orEmpty()))
        }
        val yamlRoot: Any? = try {
            Yaml().load<Any?>(String(bytes, Charsets.UTF_8))
        } catch (e: org.yaml.snakeyaml.error.YAMLException) {
            throw UtilitiesYamlException(UtilitiesYamlError.YamlParseFailure(path, e.message ?: e::class.simpleName.orEmpty()))
        }
        val projected = jsonElementFrom(yamlRoot)
        return ReadYamlOutput(
            path = path,
            bytes = bytes.size.toLong(),
            value = projected,
        )
    }

    override fun writeYaml(path: String, value: JsonElement): WriteYamlOutput {
        val yaml = Yaml()
        val tree = yamlFromJsonElement(value)
        val serialized = yaml.dump(tree)
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        val file = java.io.File(path)
        file.parentFile?.mkdirs()
        try {
            file.writeBytes(bytes)
        } catch (e: java.io.IOException) {
            throw UtilitiesYamlException(UtilitiesYamlError.YamlIoFailure(path, e.message ?: e::class.simpleName.orEmpty()))
        }
        return WriteYamlOutput(
            path = path,
            bytes = bytes.size.toLong(),
        )
    }
}

/**
 * Project a SnakeYAML-decoded value tree into a [JsonElement] so the public
 * contract stays one shape (JSON), independent of the source codec. This is
 * a pure transformation with no I/O.
 */
private fun jsonElementFrom(value: Any?): JsonElement = when (value) {
    null -> kotlinx.serialization.json.JsonNull
    is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
    is Number -> kotlinx.serialization.json.JsonPrimitive(value)
    is String -> kotlinx.serialization.json.JsonPrimitive(value)
    is Map<*, *> -> kotlinx.serialization.json.JsonObject(
        value.entries.associate { (k, v) ->
            (k.toString()) to jsonElementFrom(v)
        },
    )
    is List<*> -> kotlinx.serialization.json.JsonArray(value.map { jsonElementFrom(it) })
    else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
}

/**
 * Project a [JsonElement] back into a SnakeYAML-encodable tree.
 */
private fun yamlFromJsonElement(value: JsonElement): Any? = when (value) {
    is kotlinx.serialization.json.JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive -> when {
        value.isString -> value.content
        else -> value.content  // SnakeYAML accepts String, Boolean (true/false), Number-like.
    }
    is kotlinx.serialization.json.JsonObject -> value.mapValues { (_, v) -> yamlFromJsonElement(v) }
    is kotlinx.serialization.json.JsonArray -> value.map { yamlFromJsonElement(it) }
}

// ─────────────────────────────────────────────────────────────────────────────
// DSL extensions (typed Kotlin facades over the generic registryStep primitive).
// ─────────────────────────────────────────────────────────────────────────────

fun StageScope.readYaml(path: String) =
    registryStep(
        stepKey = ReadYamlStepDefinition.KEY,
        encodedInput = ReadYamlCodec.encode(ReadYamlInput(path)),
    )

fun StageScope.writeYaml(path: String, value: JsonElement) =
    registryStep(
        stepKey = WriteYamlStepDefinition.KEY,
        encodedInput = WriteYamlCodec.encode(WriteYamlInput(path, value)),
    )
