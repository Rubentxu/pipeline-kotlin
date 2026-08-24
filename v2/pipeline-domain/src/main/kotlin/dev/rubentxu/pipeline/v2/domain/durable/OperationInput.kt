package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull

/**
 * Immutable, serializable carrier for the input of a durable operation.
 *
 * Encoded as canonical JSON for fingerprint computation. The [validate] function
 * enforces size and content constraints that prevent non-determinism and
 * ensure the fingerprint is safe to use as a cache key.
 *
 * @param stepId   Stable identifier for the step being executed.
 * @param params   Arbitrary JSON parameters passed to the step.
 * @param runId    Deterministic run identifier derived from script path + content.
 * @param attempt  Monotonically increasing attempt number within the run (≥ 1).
 *
 * @see <a href="design.md §E4-01">Design §E4-01</a>
 */
@Serializable
data class OperationInput(
    val stepId: String,
    val params: Map<String, JsonElement>,
    val runId: String,
    val attempt: Int,
) {
    init {
        require(stepId.isNotBlank()) { "stepId must not be blank" }
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(attempt >= 1) { "attempt must be >= 1, got $attempt" }
        validate(params)
    }

    companion object {
        /** Maximum serialized size in bytes (64 KiB). */
        private const val MAX_SIZE_BYTES = 64 * 1024

        /** Secret-like parameter key patterns that are forbidden. */
        private val SECRET_PATTERNS = setOf(
            "password", "secret", "token", "api_key", "apikey",
            "private_key", "privatekey", "credential", "auth"
        )

        /**
         * Validates that the parameter map satisfies safety constraints:
         * - No secret keys
         * - No unbounded string values (> 64 KiB when serialized)
         * - No Throwable values (not serializable)
         */
        fun validate(params: Map<String, JsonElement>) {
            for ((key, value) in params) {
                val lowerKey = key.lowercase()
                require(!SECRET_PATTERNS.any { lowerKey.contains(it) }) {
                    "Parameter key '$key' looks like a secret and is not allowed in durable inputs"
                }
                val serialized = value.toString()
                require(serialized.length <= MAX_SIZE_BYTES) {
                    "Parameter '$key' value exceeds maximum size of $MAX_SIZE_BYTES bytes"
                }
            }
        }
    }
}
