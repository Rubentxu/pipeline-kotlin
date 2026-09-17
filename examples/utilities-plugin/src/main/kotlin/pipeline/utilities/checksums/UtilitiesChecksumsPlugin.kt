package pipeline.utilities.checksums

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
import java.security.MessageDigest

/**
 * LFC-2E2-EXPANSION U5 — multi-algorithm checksums (md5, sha1, sha512)
 * complementing the existing sha256.
 *
 * **Design choice**: the three new families (md5, sha1, sha512) are NOT
 * one generic "checksum(anyAlgo)" Step that takes the algorithm as a string.
 * Each algorithm is a distinct `StepKey` with its own `StepContract`,
 * `StepCodec`, and DSL extension. The shared digest logic lives in a single
 * typed [HashAlgorithm] enum + [ChecksumOperations] port — the enum is
 * closed and exhaustive, so the handler cannot be passed an unknown algorithm
 * at runtime.
 *
 * Rationale:
 *   - Keeps StepKey stability (callers target `utilities.md5`, not
 *     `utilities.checksum` with a side-channel algorithm parameter).
 *   - Algorithm names are typed, not stringly typed. Compiler enforces
 *     exhaustiveness when `when`-ing on [HashAlgorithm].
 *   - Production core stays unaware of MD5/SHA-1/SHA-512 entirely.
 *
 *   - `utilities.md5` — compute the MD5 (128-bit) digest of a file's content.
 *   - `utilities.sha1` — compute the SHA-1 (160-bit) digest of a file's content.
 *   - `utilities.sha512` — compute the SHA-512 (512-bit) digest of a file's content.
 *
 * Depends ONLY on the public SDK contracts (`pipeline-domain` +
 * `pipeline-scripting-api`) and JDK `java.security.MessageDigest`. No new
 * external dependency.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Typed failure ADT (LFC-2E2-EXPANSION U5 — checksums hardening).
// ────��─────────────────────────────��──────────────────────────────────────────

sealed interface UtilitiesChecksumError {
    /** The target file does not exist or is not readable. */
    data class ChecksumNotFound(val path: String) : UtilitiesChecksumError

    /** A filesystem-level I/O failure other than NotFound. */
    data class ChecksumIoFailure(val path: String, val reason: String) : UtilitiesChecksumError
}

class UtilitiesChecksumException(val reason: UtilitiesChecksumError) :
    RuntimeException("utilities.checksums: ${reason::class.simpleName}: ${reason.describe()}")

private fun UtilitiesChecksumError.describe(): String = when (this) {
    is UtilitiesChecksumError.ChecksumNotFound -> "file not found: $path"
    is UtilitiesChecksumError.ChecksumIoFailure -> "I/O failure at $path: $reason"
}

// ─────────────────────────────────────────────────────────────────────────────
// Typed closed algorithm ADT (NOT a string, NOT an int).
// ─────────────────────────────────────────────────────────────────────────────

enum class HashAlgorithm(val jdkName: String, val expectedHexChars: Int) {
    MD5("MD5", 32),
    SHA1("SHA-1", 40),
    SHA512("SHA-512", 128);

    companion object {
        /**
         * Map an algorithm name (canonical) to a closed ADT variant. Throws
         * IllegalArgumentException for unknown names — the caller is the
         * plugin layer (which constructs HashAlgorithm values directly), so
         * this method is only used by the codec decode path for defence.
         */
        fun fromName(name: String): HashAlgorithm = entries.firstOrNull { it.jdkName == name }
            ?: throw IllegalArgumentException("unknown algorithm: $name")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Capability port (typed narrow surface).
// ─────────────────────────────────────────────────────────────────────────────

interface ChecksumOperations {
    @Throws(UtilitiesChecksumException::class)
    fun checksum(path: String, algorithm: HashAlgorithm): ChecksumOutput
}

@Serializable
data class ChecksumOutput(
    val path: String,
    val algorithm: String,
    val digest: String,
    val bytes: Long,
)

// ─────────────────────────────────────────────────────────────────────────────
// Step families — one per algorithm.
// ─────────────────────────────────────────────────────────────────────────────

// ── md5 ───────────────────────────────────────────────────────────────

@Serializable
data class Md5Input(val path: String)

object Md5Codec : StepCodec<Md5Input> {
    private val json = Json { encodeDefaults = true }
    override fun encode(value: Md5Input): EncodedStepValue =
        EncodedStepValue(json.encodeToString(Md5Input.serializer(), value))
    override fun decode(encoded: EncodedStepValue): Md5Input =
        json.decodeFromString(Md5Input.serializer(), encoded.value)
}

object ChecksumOutputCodec : StepCodec<ChecksumOutput> {
    private val json = Json { encodeDefaults = true }
    override fun encode(value: ChecksumOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(ChecksumOutput.serializer(), value))
    override fun decode(encoded: EncodedStepValue): ChecksumOutput =
        json.decodeFromString(ChecksumOutput.serializer(), encoded.value)
}

object Md5StepDefinition : StepDefinition<Md5Input, ChecksumOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.md5")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "md5",
            configRef = "",
            pluginId = UtilitiesChecksumsContributor.COORDINATE,
            pluginVersion = UtilitiesChecksumsContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = Md5Codec,
        outputCodec = ChecksumOutputCodec,
        requiredCapabilities = setOf(UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY),
    )

    override val handler = StepHandler<Md5Input, ChecksumOutput> { input, ctx ->
        val ops: ChecksumOperations = ctx.capabilities.get(
            UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY,
        )
        ops.checksum(input.path, HashAlgorithm.MD5)
    }
}

// ── sha1 ──────────────────────────────────────────────────────────────

@Serializable
data class Sha1Input(val path: String)

object Sha1Codec : StepCodec<Sha1Input> {
    private val json = Json { encodeDefaults = true }
    override fun encode(value: Sha1Input): EncodedStepValue =
        EncodedStepValue(json.encodeToString(Sha1Input.serializer(), value))
    override fun decode(encoded: EncodedStepValue): Sha1Input =
        json.decodeFromString(Sha1Input.serializer(), encoded.value)
}

object Sha1StepDefinition : StepDefinition<Sha1Input, ChecksumOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.sha1")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "sha1",
            configRef = "",
            pluginId = UtilitiesChecksumsContributor.COORDINATE,
            pluginVersion = UtilitiesChecksumsContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = Sha1Codec,
        outputCodec = ChecksumOutputCodec,
        requiredCapabilities = setOf(UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY),
    )

    override val handler = StepHandler<Sha1Input, ChecksumOutput> { input, ctx ->
        val ops: ChecksumOperations = ctx.capabilities.get(
            UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY,
        )
        ops.checksum(input.path, HashAlgorithm.SHA1)
    }
}

// ── sha512 ────────────────────────────────────────────────────────────

@Serializable
data class Sha512Input(val path: String)

object Sha512Codec : StepCodec<Sha512Input> {
    private val json = Json { encodeDefaults = true }
    override fun encode(value: Sha512Input): EncodedStepValue =
        EncodedStepValue(json.encodeToString(Sha512Input.serializer(), value))
    override fun decode(encoded: EncodedStepValue): Sha512Input =
        json.decodeFromString(Sha512Input.serializer(), encoded.value)
}

object Sha512StepDefinition : StepDefinition<Sha512Input, ChecksumOutput> {
    val KEY: PluginStepId = PluginStepId("utilities.sha512")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "sha512",
            configRef = "",
            pluginId = UtilitiesChecksumsContributor.COORDINATE,
            pluginVersion = UtilitiesChecksumsContributor.PLUGIN_VERSION,
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = Sha512Codec,
        outputCodec = ChecksumOutputCodec,
        requiredCapabilities = setOf(UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY),
    )

    override val handler = StepHandler<Sha512Input, ChecksumOutput> { input, ctx ->
        val ops: ChecksumOperations = ctx.capabilities.get(
            UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY,
        )
        ops.checksum(input.path, HashAlgorithm.SHA512)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Contributor + capability token.
// ─────────────────────────────────────────────────────────────────────────────

object UtilitiesChecksumsContributor {
    const val COORDINATE: String = pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE
    const val PLUGIN_VERSION: String = pipeline.utilities.json.UtilitiesJsonContributor.PLUGIN_VERSION

    /**
     * Shared capability across md5/sha1/sha512. Distinct from sha256's
     * `utilities.sha.operations` so the two ports cannot accidentally
     * satisfy each other — admission stays fail-closed.
     */
    val UTILITIES_CHECKSUMS_CAPABILITY: StepCapability =
        StepCapability("utilities.checksums.operations")
}

// ─────────────────────────────────────────────────────────────────────────────
// Default FS-backed capability implementation.
// ─────────────────────────────────────────────────────────────────────────────

class DefaultChecksumOperations : ChecksumOperations {
    override fun checksum(path: String, algorithm: HashAlgorithm): ChecksumOutput {
        val file = java.io.File(path)
        if (!file.exists()) {
            throw UtilitiesChecksumException(UtilitiesChecksumError.ChecksumNotFound(path))
        }
        val md: MessageDigest = try {
            MessageDigest.getInstance(algorithm.jdkName)
        } catch (e: java.security.NoSuchAlgorithmException) {
            // Defensive — JDK should always have MD5/SHA-1/SHA-512. If not, surface
            // as I/O failure rather than letting the algorithm leak into the contract.
            throw UtilitiesChecksumException(
                UtilitiesChecksumError.ChecksumIoFailure(path, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
        val bytes: Long
        val digest: String = try {
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                var total = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                    total += read
                }
                bytes = total
                md.digest().toHex()
            }
        } catch (e: java.io.IOException) {
            throw UtilitiesChecksumException(
                UtilitiesChecksumError.ChecksumIoFailure(path, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
        return ChecksumOutput(
            path = path,
            algorithm = algorithm.jdkName,
            digest = digest,
            bytes = bytes,
        )
    }
}

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(((b.toInt() ushr 4) and 0xF).toString(16))
        sb.append((b.toInt() and 0xF).toString(16))
    }
    return sb.toString()
}

// ─────────────────────────────────────────────────────────────────────────────
// DSL extensions (typed Kotlin facades over the generic registryStep primitive).
// ─────────────────────────────────────────────────────────────────────────────

fun StageScope.md5(path: String) =
    registryStep(
        stepKey = Md5StepDefinition.KEY,
        encodedInput = Md5Codec.encode(Md5Input(path)),
    )

fun StageScope.sha1(path: String) =
    registryStep(
        stepKey = Sha1StepDefinition.KEY,
        encodedInput = Sha1Codec.encode(Sha1Input(path)),
    )

fun StageScope.sha512(path: String) =
    registryStep(
        stepKey = Sha512StepDefinition.KEY,
        encodedInput = Sha512Codec.encode(Sha512Input(path)),
    )
