package com.pipeline.v2.scripting

import java.security.MessageDigest

/**
 * Produces a SHA-256 hex string from the given byte arrays.
 */
object CacheKey {
    private val digest = MessageDigest.getInstance("SHA-256")

    /**
     * Computes sha256Hex(input.joinToString("|")).
     */
    fun sha256Hex(vararg parts: String): String {
        val input = parts.joinToString("|")
        synchronized(digest) {
            digest.reset()
            return digest.digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
