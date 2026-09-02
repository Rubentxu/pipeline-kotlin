package dev.rubentxu.pipeline.v2.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Stable identity of one pipeline definition. */
@JvmInline
value class DefinitionId(val value: String) {
    init {
        require(value.isNotBlank()) { "DefinitionId value must not be blank" }
    }
}

/** Identity of one invocation of a pipeline definition. */
@JvmInline
value class RunId(val value: String) {
    init {
        require(value.isNotBlank()) { "RunId value must not be blank" }
    }
}

/** Generates a new identity for a pipeline invocation. */
fun interface RunIdGenerator {
    fun next(): RunId
}

data class DefinitionIdentityInput(
    val source: String,
    val compatibilityVersion: String,
    val semanticInputs: Map<String, String> = emptyMap(),
) {
    init {
        require(canonicalizeSource(source).isNotBlank()) { "source must not be blank" }
        require(compatibilityVersion.isNotBlank()) { "compatibilityVersion must not be blank" }
        require(semanticInputs.keys.none(String::isBlank)) { "semanticInputs keys must not be blank" }
    }
}

/**
 * Deterministic identity functions used by the local-first domain.
 *
 * The definition algorithm intentionally preserves the legacy format so that
 * existing durable data remains addressable while callers migrate to the
 * typed contract. Invocation identity is deliberately not derived here:
 * repeated invocations of one definition must be distinguishable.
 */
object DeterministicIdGenerator {
    fun definitionId(input: DefinitionIdentityInput): DefinitionId {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(TYPED_DEFINITION_MAGIC)
                output.writeInt(TYPED_DEFINITION_FORMAT_VERSION)
                output.writeLengthPrefixed(canonicalizeSource(input.source))
                output.writeLengthPrefixed(input.compatibilityVersion)
                output.writeInt(input.semanticInputs.size)
                input.semanticInputs.toSortedMap().forEach { (key, value) ->
                    output.writeLengthPrefixed(key)
                    output.writeLengthPrefixed(value)
                }
            }
            bytes.toByteArray()
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return DefinitionId(digest.toHex())
    }

    /**
     * Derives the canonical definition identity from the legacy source tuple.
     * The 36-character SHA-256 prefix is part of the compatibility contract.
     */
    fun definitionId(scriptPath: String, scriptContent: String): DefinitionId {
        require(scriptPath.isNotBlank()) { "scriptPath must not be blank" }
        return DefinitionId(legacyDigest("$scriptPath|$scriptContent"))
    }

    private fun legacyDigest(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).toHex().take(36)
    }

    private const val TYPED_DEFINITION_FORMAT_VERSION = 1
    private val TYPED_DEFINITION_MAGIC = "pipeline-definition-identity\u0000".toByteArray(Charsets.UTF_8)
}

private fun canonicalizeSource(source: String): String =
    source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

private fun DataOutputStream.writeLengthPrefixed(value: String) {
    val encoded = value.toByteArray(Charsets.UTF_8)
    writeInt(encoded.size)
    write(encoded)
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
