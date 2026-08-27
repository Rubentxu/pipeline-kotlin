package dev.rubentxu.pipeline.v2.credentials.local

import java.nio.ByteOrder

/**
 * KDF parameters for Argon2id.
 *
 * ## OWASP Floor (2023)
 *
 * m ≥ 19456 KiB, t ≥ 2, p ≥ 1
 *
 * These params are persisted in the store header and upgraded on next put/rotate
 * if the persisted params are below the current OWASP floor.
 */
data class KdfParams(
    val m: Int,   // Memory in KiB
    val t: Int,   // Iterations
    val p: Int,   // Parallelism
    val salt: ByteArray,
) {
    companion object {
        /** OWASP floor params (19456 KiB, t=2, p=1) */
        val OWASP_MIN = KdfParams(
            m = 19456,
            t = 2,
            p = 1,
            salt = ByteArray(0),  // Salt is stored separately
        )

        const val SALT_SIZE_BYTES = 16
    }

    /**
     * Returns true if these params are below the OWASP floor.
     */
    fun isBelowCurrentFloor(): Boolean =
        m < OWASP_MIN.m || t < OWASP_MIN.t || p < OWASP_MIN.p

    /**
     * Returns a KdfParams upgraded to OWASP floor.
     * Only upgrades m/t/p, keeps the same salt.
     */
    fun upgradeToFloor(): KdfParams = copy(
        m = maxOf(m, OWASP_MIN.m),
        t = maxOf(t, OWASP_MIN.t),
        p = maxOf(p, OWASP_MIN.p),
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KdfParams
        return m == other.m && t == other.t && p == other.p && salt.contentEquals(other.salt)
    }

    override fun hashCode(): Int {
        var result = m
        result = 31 * result + t
        result = 31 * result + p
        result = 31 * result + salt.contentHashCode()
        return result
    }
}
