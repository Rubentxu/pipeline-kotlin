package dev.rubentxu.pipeline.v2.output

import java.security.MessageDigest

/**
 * M3 — a deterministic content hash of the committed bytes in `[from, to)`
 * of a stream.
 *
 * ## Algorithm
 *
 * The default is [DEFAULT_ALGORITHM] ("SHA-256"), the same algorithm the
 * journal's `Fingerprint` uses (`OperationJournal.beginOperation`).
 * The value class itself does NOT carry the algorithm name (the hash
 * is opaque at the wire), so a future hardened SHA-3-256 may ship
 * without an ABI break.
 *
 * ## Idempotency
 *
 * `OutputDigest` is a deterministic function of `(stream, from, to)`:
 * for any two calls that name the same `(from, to)` range of the same
 * committed bytes, the digest is identical.
 *
 * The wire shape is the **64-lowercase-hex** encoding of SHA-256. The
 * in-memory shape is the same hex string for stable `equals` / `hashCode`
 * across JVM restarts and cross-process round-trips.
 *
 * @property hex lowercase 64-char hex digest (SHA-256 output is 32 bytes
 *            = 64 hex chars)
 */
@JvmInline
value class OutputDigest(val hex: String) {
    init {
        require(hex.length == 64) { "expected 64-hex SHA-256 digest, got ${hex.length} chars" }
        require(hex.all { it in HEX_CHARS }) { "OutputDigest.hex must be lowercase 0-9a-f, got $hex" }
    }

    override fun toString(): String = "OutputDigest(${hex.take(16)}…)"

    companion object {
        /** Default digest algorithm. Matches the journal's `Fingerprint`. */
        const val DEFAULT_ALGORITHM: String = "SHA-256"

        /** Lookup table for hex encoding (lowercase 0-9a-f). */
        private val HEX_CHARS = ('0'..'9') + ('a'..'f')

        /** The all-zero digest — used as a placeholder / "no digest". */
        val EMPTY: OutputDigest = OutputDigest("0".repeat(64))

        /** Compute the SHA-256 digest of [bytes] and return it as an [OutputDigest]. */
        fun sha256Of(bytes: ByteArray): OutputDigest {
            val md = MessageDigest.getInstance(DEFAULT_ALGORITHM)
            val out = md.digest(bytes)
            return OutputDigest(out.toHex())
        }

        private fun ByteArray.toHex(): String {
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_CHARS[v ushr 4])
                sb.append(HEX_CHARS[v and 0x0F])
            }
            return sb.toString()
        }
    }
}