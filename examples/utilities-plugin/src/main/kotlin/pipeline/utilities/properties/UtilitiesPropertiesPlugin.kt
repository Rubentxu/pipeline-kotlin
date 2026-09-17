package pipeline.utilities.properties

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Properties

/**
 * LFC-2E2-EXPANSION U3 — properties file support for the first OFFICIAL_PLUGIN
 * family.
 *
 * Mirrors the U1 JSON + U2 YAML hardening pattern: a typed
 * [UtilitiesPropertiesError] sealed ADT, a typed [UtilitiesPropertiesException]
 * carrier, and a capability port [UtilitiesPropertiesOperations] that
 * implementations MUST throw on every failure path.
 *
 * Adds two Step families to the existing `pipeline.utilities.json@1.0.0`
 * plugin coordinate (kept for continuity; the coordinate is the plugin identity,
 * not the family surface):
 *
 *   - `utilities.readProperties` — read a `.properties` file, decode, return
 *     the key/value pairs as a JSON object (string→string map).
 *   - `utilities.writeProperties` — write a JSON object to a `.properties` file.
 *
 * Depends ONLY on the public SDK contracts (`pipeline-domain` +
 * `pipeline-scripting-api`) and the JDK's `java.util.Properties`. No new
 * external dependency; the plugin remains transitive-free.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Typed failure ADT (LFC-2E2-EXPANSION U3 — properties hardening).
// ─────────────────────────────────────────────────────────────────────────────

sealed interface UtilitiesPropertiesError {
    /** The target file does not exist or is not readable. */
    data class PropertiesNotFound(val path: String) : UtilitiesPropertiesError

    /** A filesystem-level I/O failure other than NotFound (permission denied, EIO, etc.). */
    data class PropertiesIoFailure(val path: String, val reason: String) : UtilitiesPropertiesError
}

class UtilitiesPropertiesException(val reason: UtilitiesPropertiesError) :
    RuntimeException("utilities.properties: ${reason::class.simpleName}: ${reason.describe()}")

private fun UtilitiesPropertiesError.describe(): String = when (this) {
    is UtilitiesPropertiesError.PropertiesNotFound -> "file not found: $path"
    is UtilitiesPropertiesError.PropertiesIoFailure -> "I/O failure at $path: $reason"
}

// ─────────────────────────────────────────────────────────────────────────────
// Capability port (typed narrow surface).
// ─────────────────────────────────────────────────────────────────────────────

interface UtilitiesPropertiesOperations {
    @Throws(UtilitiesPropertiesException::class)
    fun readProperties(path: String): ReadPropertiesOutput

    @Throws(UtilitiesPropertiesException::class)
    fun writeProperties(path: String, value: JsonObject): WritePropertiesOutput
}

// ─────────────────────────────────────────────────────────────────────────────
// readProperties — read a .properties file and return the entries as a JSON object.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ReadPropertiesInput(val path: String)

@Serializable
data class ReadPropertiesOutput(
    val path: String,
    val bytes: Long,
    val entries: JsonObject,
    val entryCount: Int,
)

object ReadPropertiesCodec : StepCodec<ReadPropertiesInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun encode(value: ReadPropertiesInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(ReadPropertiesInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): ReadPropertiesInput =
        json.decodeFromString(ReadPropertiesInput.serializer(), encoded.value)
}

object ReadPropertiesOutputCodec : StepCodec<ReadPropertiesOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: ReadPropertiesOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(ReadPropertiesOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): ReadPropertiesOutput =
        json.decodeFromString(ReadPropertiesOutput.serializer(), encoded.value)
}

object ReadPropertiesStepDefinition : StepDefinition<ReadPropertiesInput, ReadPropertiesOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.readProperties")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "readProperties",
            configRef = "",
            pluginId = UtilitiesPropertiesContributor.COORDINATE,
            pluginVersion = UtilitiesPropertiesContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = ReadPropertiesCodec,
        outputCodec = ReadPropertiesOutputCodec,
        requiredCapabilities = setOf(UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY),
    )

    override val handler = StepHandler<ReadPropertiesInput, ReadPropertiesOutput> { input, ctx ->
        val ops: UtilitiesPropertiesOperations = ctx.capabilities.get(
            UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY,
        )
        ops.readProperties(input.path)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// writeProperties — write a JSON object to a .properties file.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class WritePropertiesInput(
    val path: String,
    val value: JsonObject,
)

@Serializable
data class WritePropertiesOutput(
    val path: String,
    val bytes: Long,
)

object WritePropertiesCodec : StepCodec<WritePropertiesInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun encode(value: WritePropertiesInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(WritePropertiesInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): WritePropertiesInput =
        json.decodeFromString(WritePropertiesInput.serializer(), encoded.value)
}

object WritePropertiesOutputCodec : StepCodec<WritePropertiesOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: WritePropertiesOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(WritePropertiesOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): WritePropertiesOutput =
        json.decodeFromString(WritePropertiesOutput.serializer(), encoded.value)
}

object WritePropertiesStepDefinition : StepDefinition<WritePropertiesInput, WritePropertiesOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.writeProperties")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "writeProperties",
            configRef = "",
            pluginId = UtilitiesPropertiesContributor.COORDINATE,
            pluginVersion = UtilitiesPropertiesContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = WritePropertiesCodec,
        outputCodec = WritePropertiesOutputCodec,
        requiredCapabilities = setOf(UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY),
    )

    override val handler = StepHandler<WritePropertiesInput, WritePropertiesOutput> { input, ctx ->
        val ops: UtilitiesPropertiesOperations = ctx.capabilities.get(
            UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY,
        )
        ops.writeProperties(input.path, input.value)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Contributor + capability token.
// ─────────────────────────────────────────────────────────────────────────────

object UtilitiesPropertiesContributor {
    const val COORDINATE: String = pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE
    const val PLUGIN_VERSION: String = pipeline.utilities.json.UtilitiesJsonContributor.PLUGIN_VERSION

    /** Capability required by `readProperties` and `writeProperties`. */
    val UTILITIES_PROPERTIES_CAPABILITY: StepCapability =
        StepCapability("utilities.properties.operations")
}

// ─────────────────────────────────────────────────────────────────────────────
// Default FS-backed capability implementation.
// ─────────────────────────────────────────────────────────────────────────────

class DefaultUtilitiesPropertiesOperations : UtilitiesPropertiesOperations {
    override fun readProperties(path: String): ReadPropertiesOutput {
        val file = java.io.File(path)
        if (!file.exists()) {
            throw UtilitiesPropertiesException(UtilitiesPropertiesError.PropertiesNotFound(path))
        }
        val bytes = try {
            file.readBytes()
        } catch (e: java.io.IOException) {
            throw UtilitiesPropertiesException(
                UtilitiesPropertiesError.PropertiesIoFailure(path, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
        val props = Properties()
        try {
            props.load(java.io.ByteArrayInputStream(bytes))
        } catch (e: java.io.IOException) {
            throw UtilitiesPropertiesException(
                UtilitiesPropertiesError.PropertiesIoFailure(path, e.message ?: e::class.simpleName.orEmpty()),
            )
        } catch (e: java.lang.IllegalArgumentException) {
            // Malformed Properties (e.g. unescaped = in value) — surface as I/O failure
            // (Java's Properties API does not split parse vs IO failures cleanly).
            throw UtilitiesPropertiesException(
                UtilitiesPropertiesError.PropertiesIoFailure(path, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
        // Project Properties → JsonObject (string → string).
        val obj: JsonObject = JsonObject(
            props.stringPropertyNames().associateWith { name ->
                JsonPrimitive(props.getProperty(name) ?: "")
            },
        )
        return ReadPropertiesOutput(
            path = path,
            bytes = bytes.size.toLong(),
            entries = obj,
            entryCount = obj.size,
        )
    }

    override fun writeProperties(path: String, value: JsonObject): WritePropertiesOutput {
        val props = Properties()
        value.forEach { (k, v) ->
            // Project JsonElement → String (non-string types stringify; null becomes "").
            val asString: String = when (v) {
                is JsonPrimitive -> v.content
                else -> v.toString()
            }
            props.setProperty(k, asString)
        }
        val baos = java.io.ByteArrayOutputStream()
        try {
            props.store(baos, null)
        } catch (e: java.io.IOException) {
            throw UtilitiesPropertiesException(
                UtilitiesPropertiesError.PropertiesIoFailure(path, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
        val bytes = baos.toByteArray()
        val file = java.io.File(path)
        file.parentFile?.mkdirs()
        try {
            file.writeBytes(bytes)
        } catch (e: java.io.IOException) {
            throw UtilitiesPropertiesException(
                UtilitiesPropertiesError.PropertiesIoFailure(path, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
        return WritePropertiesOutput(
            path = path,
            bytes = bytes.size.toLong(),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DSL extensions (typed Kotlin facades over the generic registryStep primitive).
// ─────────────────────────────────────────────────────────────────────────────

fun StageScope.readProperties(path: String) =
    registryStep(
        stepKey = ReadPropertiesStepDefinition.KEY,
        encodedInput = ReadPropertiesCodec.encode(ReadPropertiesInput(path)),
    )

fun StageScope.writeProperties(path: String, value: JsonObject) =
    registryStep(
        stepKey = WritePropertiesStepDefinition.KEY,
        encodedInput = WritePropertiesCodec.encode(WritePropertiesInput(path, value)),
    )

/**
 * Script-safe overload. Semantics are frozen as: **`content` is a serialized
 * `.properties` document**, NOT JSON text reinterpreted as properties.
 *
 * Uniform with [pipeline.utilities.json.writeJSON] and
 * [pipeline.utilities.yaml.writeYaml]: the String is that format's own text, parsed at
 * the DSL facade into the SAME canonical typed input. Same StepKey, same
 * StepDefinition, same handler, same capability: no new key, no alternate execution
 * path, no duplicated logic.
 */
fun StageScope.writeProperties(file: String, content: String) =
    registryStep(
        stepKey = WritePropertiesStepDefinition.KEY,
        encodedInput = WritePropertiesCodec.encode(
            WritePropertiesInput(file, propertiesTextToJsonObject(file, content)),
        ),
    )

/**
 * Parses serialized `.properties` text into the SAME JsonObject projection the read
 * step produces (string -> string). Fail-closed with a typed error on malformed
 * input; Java's Properties API does not separate parse from IO failures, so both
 * surface as [UtilitiesPropertiesError.PropertiesIoFailure].
 */
private fun propertiesTextToJsonObject(file: String, content: String): JsonObject {
    val props = java.util.Properties()
    try {
        props.load(java.io.StringReader(content))
    } catch (e: java.io.IOException) {
        throw UtilitiesPropertiesException(
            UtilitiesPropertiesError.PropertiesIoFailure(file, e.message ?: e::class.simpleName.orEmpty()),
        )
    } catch (e: java.lang.IllegalArgumentException) {
        throw UtilitiesPropertiesException(
            UtilitiesPropertiesError.PropertiesIoFailure(file, e.message ?: e::class.simpleName.orEmpty()),
        )
    }
    return JsonObject(
        props.stringPropertyNames().associateWith { name ->
            JsonPrimitive(props.getProperty(name) ?: "")
        },
    )
}
