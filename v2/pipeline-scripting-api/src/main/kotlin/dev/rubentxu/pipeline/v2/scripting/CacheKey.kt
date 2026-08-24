package dev.rubentxu.pipeline.v2.scripting

import java.security.MessageDigest

/**
 * A versioned cache key for stable script compilation caching.
 *
 * @property value The SHA-256 hex digest.
 * @property version The algorithm version tag.
 */
data class CacheKey(
    val value: String,
    val version: String,
) {
    companion object {
        const val V1 = "v1"
        const val V2 = "v2"

        private val digest = MessageDigest.getInstance("SHA-256")

        /**
         * Joins parts with `|`, then SHA-256s UTF-8 bytes.
         */
        fun sha256Hex(vararg parts: String): String {
            val input = parts.joinToString("|")
            synchronized(digest) {
                digest.reset()
                return digest.digest(input.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }
        }

        object v1 {
            fun compute(
                scriptText: String,
                sortedClasspath: String,
                kotlinVersion: String,
                hostVersion: String,
            ): CacheKey = CacheKey(
                sha256Hex(scriptText, sortedClasspath, kotlinVersion, hostVersion),
                V1
            )
        }

        object v2 {
            fun compute(
                scriptText: String,
                sortedClasspath: String,
                kotlinVersion: String,
                hostVersion: String,
            ): CacheKey = throw UnsupportedOperationException(
                "v2 reserved — algorithm not introduced in M1-R2"
            )
        }
    }
}
