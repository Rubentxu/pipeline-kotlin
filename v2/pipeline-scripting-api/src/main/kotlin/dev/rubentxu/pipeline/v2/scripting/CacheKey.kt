package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.domain.digest.Sha256

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

        /**
         * Joins parts with `|`, then SHA-256s UTF-8 bytes.
         *
         * Routed through [Sha256] (B0), which removes a `private val digest =
         * MessageDigest.getInstance("SHA-256")` held as static mutable state and guarded by
         * `synchronized`. That field was correct only because every caller took the lock and
         * called `reset()` first; the correctness depended on a discipline every future caller
         * would have to remember. The digest value is unchanged, so existing cache entries
         * still hit.
         */
        fun sha256Hex(vararg parts: String): String = Sha256.ofText(parts.joinToString("|"))

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
