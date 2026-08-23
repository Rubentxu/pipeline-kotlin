package com.pipeline.v2.domain.durable

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

/**
 * SHA-256 fingerprint of a durable operation's input, used as a cache key.
 *
 * Represented as a 64-character lowercase hex string (256 bits).
 *
 * ## Canonical JSON encoding
 *
 * The fingerprint payload is encoded with [Json].[canonical][Json.DeepRecursive], which
 * guarantees stable field ordering and consistent serialization across JVM instances.
 * This ensures cross-process determinism: the same input always produces the same fingerprint.
 *
 * ## Collision resistance
 *
 * SHA-256 over bounded input (≤ 64 KiB enforced by [OperationInput.validate]) is
 * cryptographically collision-resistant for this use case. The risk is negligible
 * and documented here per [design.md §R-B].
 *
 * @param hex 64-character lowercase hex string.
 *
 * @see <a href="design.md §E4-02">Design §E4-02</a>
 */
@JvmInline
value class Fingerprint(val hex: String) {
    init {
        require(hex.length == 64) { "Fingerprint hex must be exactly 64 characters, got ${hex.length}" }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' }) { "Fingerprint hex must contain only 0-9 and a-f, got: $hex" }
    }

    companion object {
        private val JSON = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        /**
         * Payload DTO for canonical fingerprint computation.
         *
         * The field order here is the canonical order used for SHA-256 hashing.
         * Adding fields to this class is a BREAKING CHANGE for the fingerprint contract.
         */
        @Serializable
        private data class FingerprintPayload(
            val stepId: String,
            val params: Map<String, JsonElement>,
            val runId: String,
            val attempt: Int,
            val replayPolicy: ReplayPolicy,
        )

        /**
         * Computes the SHA-256 fingerprint of a durable operation.
         *
         * The canonical payload is constructed from [input], [stepId], [replayPolicy],
         * and [attempt], then encoded as canonical JSON and hashed with SHA-256.
         *
         * @param input        The durable operation's input.
         * @param stepId       Stable step identifier.
         * @param replayPolicy The replay policy at the time of computation.
         * @param attempt      Current attempt number (≥ 1).
         * @return A 64-char hex fingerprint.
         *
         * @throws IllegalArgumentException if [attempt] is < 1.
         */
        fun compute(
            input: OperationInput,
            stepId: String,
            replayPolicy: ReplayPolicy,
            attempt: Int,
        ): Fingerprint {
            require(attempt >= 1) { "attempt must be >= 1, got $attempt" }

            val payload = FingerprintPayload(
                stepId = stepId,
                params = input.params,
                runId = input.runId,
                attempt = attempt,
                replayPolicy = replayPolicy,
            )

            val canonicalJson = JSON.encodeToString(FingerprintPayload.serializer(), payload)
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(canonicalJson.toByteArray(Charsets.UTF_8))
            val hexString = hashBytes.joinToString("") { "%02x".format(it) }
            return Fingerprint(hexString)
        }
    }
}
