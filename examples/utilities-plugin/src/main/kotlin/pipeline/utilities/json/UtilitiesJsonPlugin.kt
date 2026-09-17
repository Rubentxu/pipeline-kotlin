package pipeline.utilities.json

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
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.dsl.StageScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * First OFFICIAL_PLUGIN of the LFC-2E2 cycle (FASE 6).
 *
 * Ships three families against the frozen universal-core authoring surface
 * established by LFC-2E2-PREP (`docs/v2/00-governance/PLUGIN_AUTHORING.md`):
 *
 *   - `utilities.readJSON` — read a file, decode JSON, return typed JSON value.
 *   - `utilities.writeJSON` — write a JSON value to a file with optional pretty printing.
 *   - `utilities.sha256` — compute the SHA-256 digest of a file's content.
 *
 * Depends ONLY on public SDK contracts (pipeline-domain) and the public DSL
 * (pipeline-scripting-api). Contributes families through [StepDefinitionContributor];
 * discovered by the host runtime via ServiceLoader. Zero core/production changes.
 *
 * The capability declared is a single typed capability token
 * [UTILITIES_JSON_CAPABILITY] / [UTILITIES_SHA_CAPABILITY]. The host runtime
 * must supply it (via the typed capability registry) before any handler runs.
 * The admission policy is verified mechanically by [pipeline.utilities.json.PluginAdmissionCheck].
 */
class UtilitiesJsonContributor : StepDefinitionContributor {
    override val id: String = COORDINATE

    override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(
        ReadJsonStepDefinition,
        WriteJsonStepDefinition,
        Sha256StepDefinition,
        // LFC-2E2-EXPANSION U2: YAML families registered alongside the JSON ones
        // through the same contributor (same plugin coordinate, same JAR).
        pipeline.utilities.yaml.ReadYamlStepDefinition,
        pipeline.utilities.yaml.WriteYamlStepDefinition,
        // LFC-2E2-EXPANSION U3: properties families registered through the same
        // contributor (same plugin coordinate, same JAR).
        pipeline.utilities.properties.ReadPropertiesStepDefinition,
        pipeline.utilities.properties.WritePropertiesStepDefinition,
        // LFC-2E2-EXPANSION U4: filesystem traversal (findFiles + touch) registered
        // through the same contributor. Different shape (LIST-shaped output, timestamp
        // effect) — proves the architecture supports non-codec families.
        pipeline.utilities.filesystem.FindFilesStepDefinition,
        pipeline.utilities.filesystem.TouchStepDefinition,
        // LFC-2E2-EXPANSION U5: checksums (md5, sha1, sha512) reusing the sha256
        // pattern but with a typed closed HashAlgorithm enum inside the plugin (NOT a
        // string-keyed generic Step). Each algorithm is its own StepKey.
        pipeline.utilities.checksums.Md5StepDefinition,
        pipeline.utilities.checksums.Sha1StepDefinition,
        pipeline.utilities.checksums.Sha512StepDefinition,
    )

    companion object {
        const val COORDINATE: String = "pipeline.utilities.json"
        const val PLUGIN_VERSION: String = "1.0.0"

        /** Capability required by `readJSON` and `writeJSON`. */
        val UTILITIES_JSON_CAPABILITY: StepCapability = StepCapability("utilities.json.operations")

        /** Capability required by `sha256`. */
        val UTILITIES_SHA_CAPABILITY: StepCapability = StepCapability("utilities.sha.operations")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// readJSON — read a file and decode its content as a JSON value.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ReadJsonInput(val path: String)

@Serializable
data class ReadJsonOutput(
    val path: String,
    val bytes: Long,
    val sha256: String,
    val value: JsonElement,
)

object ReadJsonCodec : StepCodec<ReadJsonInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun encode(value: ReadJsonInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(ReadJsonInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): ReadJsonInput =
        json.decodeFromString(ReadJsonInput.serializer(), encoded.value)
}

object ReadJsonOutputCodec : StepCodec<ReadJsonOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: ReadJsonOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(ReadJsonOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): ReadJsonOutput =
        json.decodeFromString(ReadJsonOutput.serializer(), encoded.value)
}

object ReadJsonStepDefinition : StepDefinition<ReadJsonInput, ReadJsonOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.readJSON")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "readJSON",
            configRef = "",
            pluginId = UtilitiesJsonContributor.COORDINATE,
            pluginVersion = UtilitiesJsonContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = ReadJsonCodec,
        outputCodec = ReadJsonOutputCodec,
        requiredCapabilities = setOf(UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY),
    )

    override val handler = StepHandler<ReadJsonInput, ReadJsonOutput> { input, ctx ->
        // Capability access is fail-closed: missing capability rejects before
        // the handler runs. We only reach here after `ctx.capabilities.get` succeeds.
        val ops: UtilitiesJsonOperations = ctx.capabilities.get(
            UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY,
        )
        ops.readJson(input.path)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// writeJSON — write a JSON value to a file with optional pretty printing.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class WriteJsonInput(
    val path: String,
    val value: JsonElement,
    val prettyPrint: Boolean = true,
)

@Serializable
data class WriteJsonOutput(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

object WriteJsonCodec : StepCodec<WriteJsonInput> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "_type" }

    override fun encode(value: WriteJsonInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(WriteJsonInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): WriteJsonInput =
        json.decodeFromString(WriteJsonInput.serializer(), encoded.value)
}

object WriteJsonOutputCodec : StepCodec<WriteJsonOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: WriteJsonOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(WriteJsonOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): WriteJsonOutput =
        json.decodeFromString(WriteJsonOutput.serializer(), encoded.value)
}

object WriteJsonStepDefinition : StepDefinition<WriteJsonInput, WriteJsonOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.writeJSON")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "writeJSON",
            configRef = "",
            pluginId = UtilitiesJsonContributor.COORDINATE,
            pluginVersion = UtilitiesJsonContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.WRITES_WORKSPACE),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = WriteJsonCodec,
        outputCodec = WriteJsonOutputCodec,
        requiredCapabilities = setOf(UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY),
    )

    override val handler = StepHandler<WriteJsonInput, WriteJsonOutput> { input, ctx ->
        val ops: UtilitiesJsonOperations = ctx.capabilities.get(
            UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY,
        )
        ops.writeJson(input.path, input.value, input.prettyPrint)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// sha256 — compute the SHA-256 digest of a file's content.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class Sha256Input(val path: String)

@Serializable
data class Sha256Output(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

object Sha256Codec : StepCodec<Sha256Input> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun encode(value: Sha256Input): EncodedStepValue =
        EncodedStepValue(json.encodeToString(Sha256Input.serializer(), value))

    override fun decode(encoded: EncodedStepValue): Sha256Input =
        json.decodeFromString(Sha256Input.serializer(), encoded.value)
}

object Sha256OutputCodec : StepCodec<Sha256Output> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: Sha256Output): EncodedStepValue =
        EncodedStepValue(json.encodeToString(Sha256Output.serializer(), value))

    override fun decode(encoded: EncodedStepValue): Sha256Output =
        json.decodeFromString(Sha256Output.serializer(), encoded.value)
}

object Sha256StepDefinition : StepDefinition<Sha256Input, Sha256Output> {
    val KEY: PluginStepId = PluginStepId("utilities.sha256")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "sha256",
            configRef = "",
            pluginId = UtilitiesJsonContributor.COORDINATE,
            pluginVersion = UtilitiesJsonContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = Sha256Codec,
        outputCodec = Sha256OutputCodec,
        requiredCapabilities = setOf(UtilitiesJsonContributor.UTILITIES_SHA_CAPABILITY),
    )

    override val handler = StepHandler<Sha256Input, Sha256Output> { input, ctx ->
        val ops: UtilitiesShaOperations = ctx.capabilities.get(
            UtilitiesJsonContributor.UTILITIES_SHA_CAPABILITY,
        )
        ops.sha256(input.path)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Typed failure ADT (LFC-2E2-EXPANSION U1 — JSON hardening).
//
// The plugin family exposes a sealed [UtilitiesJsonError] ADT plus a typed
// [UtilitiesJsonException] that carries it. Every failure path inside the
// default FS-backed capability implementation raises this typed exception,
// so a handler / host can branch exhaustively on the failure class without
// parsing stringly-typed exception messages. The boundary preserves the
// original exception as the `cause` of the resulting `StepFailed`-equivalent
// engine failure; tests and observability consumers can inspect it.
// ─────────────────────────────────────────────────────────────────────────────

sealed interface UtilitiesJsonError {
    /** The target file does not exist or is not readable. */
    data class JsonNotFound(val path: String) : UtilitiesJsonError

    /** The target file is reachable but its content is not valid JSON. */
    data class JsonParseFailure(val path: String, val reason: String) : UtilitiesJsonError

    /** A filesystem-level I/O failure other than NotFound/Parse (permission denied, EIO, etc.). */
    data class JsonIoFailure(val path: String, val reason: String) : UtilitiesJsonError
}

/**
 * Typed exception thrown by the JSON capability operations. Carries a sealed
 * [UtilitiesJsonError] variant so a consumer can switch on the failure class
 * exhaustively (`when (e.reason)` is the only `when` needed).
 *
 * NOTE: this exception lives in the plugin package so the host runtime and
 * core coordinator remain unaware of it. The boundary catches it as a generic
 * `Exception` (no Step-specific branch), preserves it as the cause of the
 * resulting engine failure, and surfaces it to consumers that read the
 * `RunOutcome.Failure.failure.cause` chain.
 */
class UtilitiesJsonException(val reason: UtilitiesJsonError) :
    RuntimeException("utilities.json: ${reason::class.simpleName}: ${reason.describe()}")

private fun UtilitiesJsonError.describe(): String = when (this) {
    is UtilitiesJsonError.JsonNotFound -> "file not found: $path"
    is UtilitiesJsonError.JsonParseFailure -> "parse failure at $path: $reason"
    is UtilitiesJsonError.JsonIoFailure -> "I/O failure at $path: $reason"
}

// ─────────────────────────────────────────────────────────────────────────────
// Capability ports (typed narrow surfaces)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Typed narrow capability surface for read/write JSON file operations.
 * The host runtime supplies a concrete implementation at composition time;
 * the plugin declares it via [UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY].
 *
 * Every failure path in a conforming implementation MUST raise a
 * [UtilitiesJsonException] carrying a typed [UtilitiesJsonError] reason.
 * Callers are expected to handle the exception exhaustively (`when (e.reason)`).
 */
interface UtilitiesJsonOperations {
    @Throws(UtilitiesJsonException::class)
    fun readJson(path: String): ReadJsonOutput

    @Throws(UtilitiesJsonException::class)
    fun writeJson(path: String, value: JsonElement, prettyPrint: Boolean): WriteJsonOutput
}

/**
 * Typed narrow capability surface for SHA-256 file digest operations.
 */
interface UtilitiesShaOperations {
    @Throws(UtilitiesJsonException::class)
    fun sha256(path: String): Sha256Output
}

// ─────────────────────────────────────────────────────────────────────────────
// Default capability implementations (FS + standard library).
//
// A production host would supply its own implementations through the typed
// capability registry. For unit tests and the example fixture, we ship a
// default FS-backed implementation here. The plugin declares it through the
// same typed [StepCapability] token the runtime would supply.
// ─────────────────────────────────────────────────────────────────────────────

class DefaultUtilitiesJsonOperations : UtilitiesJsonOperations {
    override fun readJson(path: String): ReadJsonOutput {
        val file = java.io.File(path)
        if (!file.exists()) {
            throw UtilitiesJsonException(UtilitiesJsonError.JsonNotFound(path))
        }
        val bytes = try {
            file.readBytes()
        } catch (e: java.io.IOException) {
            throw UtilitiesJsonException(UtilitiesJsonError.JsonIoFailure(path, e.message ?: e::class.simpleName.orEmpty()))
        }
        val element = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8))
        } catch (e: kotlinx.serialization.SerializationException) {
            throw UtilitiesJsonException(UtilitiesJsonError.JsonParseFailure(path, e.message ?: e::class.simpleName.orEmpty()))
        }
        return ReadJsonOutput(
            path = path,
            bytes = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
            value = element,
        )
    }

    override fun writeJson(path: String, value: JsonElement, prettyPrint: Boolean): WriteJsonOutput {
        val json = if (prettyPrint) {
            Json { this.prettyPrint = true; encodeDefaults = true }
        } else {
            Json { encodeDefaults = true }
        }
        val serialized = json.encodeToString(JsonElement.serializer(), value)
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        val file = java.io.File(path)
        file.parentFile?.mkdirs()
        try {
            file.writeBytes(bytes)
        } catch (e: java.io.IOException) {
            throw UtilitiesJsonException(UtilitiesJsonError.JsonIoFailure(path, e.message ?: e::class.simpleName.orEmpty()))
        }
        return WriteJsonOutput(
            path = path,
            bytes = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
        )
    }
}

class DefaultUtilitiesShaOperations : UtilitiesShaOperations {
    override fun sha256(path: String): Sha256Output {
        val file = java.io.File(path)
        if (!file.exists()) {
            throw UtilitiesJsonException(UtilitiesJsonError.JsonNotFound(path))
        }
        val bytes = try {
            file.readBytes()
        } catch (e: java.io.IOException) {
            throw UtilitiesJsonException(UtilitiesJsonError.JsonIoFailure(path, e.message ?: e::class.simpleName.orEmpty()))
        }
        return Sha256Output(
            path = path,
            bytes = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
        )
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { "%02x".format(it) }
}

/**
 * Optional typed helper for callers that want a simple JSON value without
 * going through the codec machinery (e.g. composing [WriteJsonInput] from a
 * literal in the DSL).
 */
fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            null -> JsonPrimitive(null as String?)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
)

// ─────────────────────────────────────────────────────────────────────────────
// DSL facade (EP-5 / external plugin authoring pattern).
//
// The plugin teaches the DSL its own Step families; core, compiler and
// coordinator never learn the names `readJSON` / `writeJSON` / `sha256`. Each
// helper is a thin wrapper over the generic `registryStep` primitive that
// lowers to `StepSpec.RegistryStepSpec` (declarative IR) and is executed by the
// canonical spine; nothing here touches PipelineRun or any direct-execution
// path. The helper accepts the JSON value as a String for `writeJSON`; users
// who need a typed JSON value can use [jsonObjectOf] to compose one in their
// script.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `pipeline { stages { stage(...) { utilities.readJSON(path = "...") } } }`.
 *
 * Reads [path] as a UTF-8 JSON file and returns the decoded JSON value as the
 * Step's typed output (encoded in the [StepSpec.RegistryStepSpec.encodedInput]
 * envelope at script-compile time, projected as a typed [ReadJsonOutput] at
 * runtime by the canonical spine).
 */
fun StageScope.readJSON(path: String) {
    registryStep(
        stepKey = ReadJsonStepDefinition.KEY,
        encodedInput = ReadJsonCodec.encode(ReadJsonInput(path)),
    )
}

/**
 * `pipeline { stages { stage(...) { utilities.writeJSON(path = "...", value = "...", prettyPrint = true) } } }`.
 *
 * Writes the supplied JSON literal to [path] with optional pretty-printing.
 * The [value] string is parsed as a [JsonElement] at script-compile time so the
 * Step's typed input shape is honored.
 */
fun StageScope.writeJSON(path: String, value: String, prettyPrint: Boolean = true) {
    val parsed: JsonElement = Json.parseToJsonElement(value)
    registryStep(
        stepKey = WriteJsonStepDefinition.KEY,
        encodedInput = WriteJsonCodec.encode(WriteJsonInput(path, parsed, prettyPrint)),
    )
}

/**
 * `pipeline { stages { stage(...) { utilities.sha256(path = "...") } } }`.
 *
 * Computes the SHA-256 hex digest of the file at [path]. Returns the typed
 * [Sha256Output] (bytes + hex digest).
 */
fun StageScope.sha256(path: String) {
    registryStep(
        stepKey = Sha256StepDefinition.KEY,
        encodedInput = Sha256Codec.encode(Sha256Input(path)),
    )
}
