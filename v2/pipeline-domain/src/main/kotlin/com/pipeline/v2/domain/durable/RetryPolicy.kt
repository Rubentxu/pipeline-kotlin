package com.pipeline.v2.domain.durable

/**
 * Retry policy for durable step execution.
 *
 * Each attempt waits for an exponential backoff before executing:
 * delay = baseMs * 2^(attempt-1) + jitter
 * where jitter is a random value in [0, jitterMs].
 *
 * @param maxAttempts Maximum number of attempts (must be >= 1).
 * @param baseMs Base delay in milliseconds for exponential backoff.
 * @param jitterMs Maximum jitter to add in milliseconds.
 */
data class RetryPolicy(
    val maxAttempts: Int,
    val baseMs: Long,
    val jitterMs: Long,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
        require(baseMs >= 0) { "baseMs must be >= 0, got $baseMs" }
        require(jitterMs >= 0) { "jitterMs must be >= 0, got $jitterMs" }
    }

    companion object {
        /**
         * Default retry policy with no retry (single attempt).
         */
        val NONE = RetryPolicy(maxAttempts = 1, baseMs = 0, jitterMs = 0)

        /**
         * Computes the backoff delay for a given attempt number.
         *
         * @param attempt The 1-based attempt number.
         * @return The delay in milliseconds to wait before starting this attempt.
         */
        fun backoffDelay(attempt: Int, baseMs: Long, jitterMs: Long): Long {
            require(attempt >= 1) { "attempt must be >= 1, got $attempt" }
            val exponentialDelay = baseMs * (1L shl (attempt - 1)) // baseMs * 2^(attempt-1)
            val jitter = if (jitterMs > 0) (Math.random() * jitterMs).toLong() else 0L
            return exponentialDelay + jitter
        }
    }

    /**
     * Computes the backoff delay for this policy's configuration.
     *
     * @param attempt The 1-based attempt number.
     * @return The delay in milliseconds.
     */
    fun backoffDelay(attempt: Int): Long = backoffDelay(attempt, baseMs, jitterMs)
}
