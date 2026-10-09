package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Stable identity of one pipeline definition. */
@JvmInline
@Serializable
value class DefinitionId(val value: String) {
    init {
        require(value.isNotBlank()) { "DefinitionId value must not be blank" }
    }
}

/** Identity of one invocation of a pipeline definition. */
@JvmInline
@Serializable
value class RunId(val value: String) {
    init {
        require(value.isNotBlank()) { "RunId value must not be blank" }
    }
}

/** Stable identity of one stage within a compiled pipeline definition. */
@JvmInline
@Serializable
value class StageId(val value: String) {
    init {
        require(value.isNotBlank()) { "StageId value must not be blank" }
    }
}

/** Stable identity of one step node within a compiled pipeline definition. */
@JvmInline
@Serializable
value class StepId(val value: String) {
    init {
        require(value.isNotBlank()) { "StepId value must not be blank" }
    }
}

/** Stable identity of the plugin-owned step kind represented by a step node. */
@JvmInline
@Serializable
value class PluginStepId(val value: String) {
    init {
        require(value.isNotBlank()) { "PluginStepId value must not be blank" }
    }
}

/** Zero-based attempt identity attached to a runtime execution context. */
@JvmInline
@Serializable
value class AttemptId(val value: Int) {
    init {
        require(value >= 0) { "AttemptId value must not be negative" }
    }
}

/** Stable runtime operation identity, separate from definition-local node IDs. */
@JvmInline
@Serializable
value class OperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "OperationId value must not be blank" }
    }
}

/**
 * Segment of a block's body path, encoding the child index and plugin step ID
 * for length-prefix body dispatch (ADR-0066 §1).
 *
 * Format: "{index}:{pluginStepId.value}"
 */
@JvmInline
@Serializable
value class BlockSegment(val encoded: String) {
    init { require(encoded.isNotBlank()) { "BlockSegment encoded must not be blank" } }

    constructor(index: Int, pluginStepId: PluginStepId) : this("${index}:${pluginStepId.value}")
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

        // The typed definition payload is a BINARY DataOutputStream, not text, so it goes
        // through ofBytes rather than ofText. Bytes unchanged: the previous code hashed
        // exactly this array.
        return DefinitionId(Sha256.ofBytes(payload))
    }

    /**
     * Derives the canonical definition identity from the legacy source tuple.
     * The 36-character SHA-256 prefix is part of the compatibility contract.
     */
    fun definitionId(scriptPath: String, scriptContent: String): DefinitionId {
        require(scriptPath.isNotBlank()) { "scriptPath must not be blank" }
        return DefinitionId(legacyDigest("$scriptPath|$scriptContent"))
    }

    /**
     * The 36-character legacy definition digest.
     *
     * The truncation is the compatibility contract, not a shortcut, and it is preserved here
     * byte for byte: [Sha256] returns the same 64-character lowercase hex the local `toHex`
     * produced, and `.take(36)` still cuts the same prefix.
     */
    private fun legacyDigest(input: String): String = Sha256.ofText(input).take(36)

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
