package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader for the canonical credential part format.
 *
 * A credential is a sequence of parts, each written as
 * `[nameLen:1][name:nameLen][len:4 BE][payload]`, preceded by a single part
 * count. A part that links to another credential instead of carrying bytes has
 * no length prefix: its payload is a 4-byte marker followed by the referenced
 * id, and the reader still consumes the part name before the marker.
 *
 * This is a pure reader over a buffer. It holds no store state and never touches
 * the filesystem, so the same bytes always produce the same result. The store
 * owns the surrounding concerns: encryption, the linked-ref chain, and the
 * cycle guard.
 *
 * The layout is an on-disk contract. `CredentialPartLayoutTest` pins the bytes
 * of a single part, and the fixtures under `src/test/resources/compat` were
 * written by an earlier implementation of the store, so a change here has to
 * keep reading what is already on disk.
 */
internal object CredentialPartReader {

    /**
     * Payload marker of a part that links to another credential. The
     * referenced id follows the marker, without a length prefix of its own.
     */
    private const val LINKED_REF_MARKER = -1

    /** Payload marker of a part that is declared but has no value. */
    private const val ABSENT_MARKER = 0

    /**
     * Reads a part header and returns its payload length, leaving the buffer
     * positioned at the first payload byte.
     */
    fun readPartHeader(buf: ByteBuffer): Int {
        val nameLen = buf.get().toInt() and 0xFF
        if (nameLen > buf.remaining()) {
            throw MalformedEnvelopeException(
                "Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})")
        }
        buf.position(buf.position() + nameLen)
        return buf.int // big-endian; position now at value bytes
    }

    /** Reads the payload of a part whose header is already at the buffer. */
    fun readPartValue(buf: ByteBuffer): ByteArray {
        val valueLen = readPartHeader(buf)
        val valueBytes = ByteArray(valueLen)
        buf.get(valueBytes)
        return valueBytes
    }

    /** Skips a whole part, header and payload, and returns its payload length. */
    fun skipPart(buf: ByteBuffer): Int {
        val valueLen = readPartHeader(buf)
        buf.position(buf.position() + valueLen)
        return valueLen
    }

    /**
     * Reads an inline part, after skipping [skipBefore] preceding parts of the
     * same kind.
     */
    fun readInlinePart(buf: ByteBuffer, skipBefore: Int): ByteArray {
        repeat(skipBefore) { skipPart(buf) }
        return readPartValue(buf)
    }

    /**
     * Reads the referenced id of a linked-ref part whose header is at the
     * buffer, or returns null when the part is marked absent.
     */
    fun readLinkedRefPart(buf: ByteBuffer, credentialLabel: String): CredentialsId? {
        val nameLen = buf.get().toInt() and 0xFF
        buf.position(buf.position() + nameLen)
        return when (val marker = buf.int) {
            LINKED_REF_MARKER -> {
                val refIdLen = buf.int
                val refIdBytes = ByteArray(refIdLen)
                buf.get(refIdBytes)
                CredentialsId.from(String(refIdBytes, Charsets.UTF_8))
            }
            ABSENT_MARKER -> throw MalformedEnvelopeException("Credential has no $credentialLabel part")
            else -> throw MalformedEnvelopeException("$credentialLabel must be linked-ref or absent")
        }
    }

    /**
     * Resolves the position of a named inline part within a credential whose
     * parts are stored in a fixed order, or returns null when the name is not
     * one of them.
     */
    fun partOffset(partName: String, vararg orderedParts: Pair<String, Int>): Int? =
        orderedParts.firstOrNull { (name, _) -> name == partName }?.second

    /** Wraps a buffer over [bytes] in the order the format specifies. */
    fun bufferOver(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    /**
     * The credential bytes are not readable in the form the store hands them
     * over, so the reader reports what it finds and the store decides what that
     * means. The store owns the type its callers catch.
     */
    class MalformedEnvelopeException(message: String) : RuntimeException(message)
}
