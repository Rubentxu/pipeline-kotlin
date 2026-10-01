package dev.rubentxu.pipeline.v2.domain.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WU-RP-5 / PR-014 follow-up: covers the RetryPolicy mutations pitest flagged
 * as NO_COVERAGE (backoffDelay arithmetic and constructor validation branches).
 *
 * Determinism: every assertion uses jitterMs = 0 so the exponential term is
 * exact; the jitter>0 rows assert BOUNDS (the delay is random by contract).
 *
 * Documented edge, NOT pinned as correct: `1L shl (attempt - 1)` overflows for
 * attempt >= 64 with baseMs >= 1 (negative delays). Real retry budgets never
 * reach that range; the overflow is recorded as a finding for the policy
 * owner rather than asserted into the contract.
 */
class RetryPolicyBackoffTest {

    @Test
    fun `jitter zero - the delay is the exact exponential term`() {
        val policy = RetryPolicy(maxAttempts = 3, baseMs = 100, jitterMs = 0)
        assertEquals(100L, policy.backoffDelay(1))         // 100 * 2^0
        assertEquals(200L, policy.backoffDelay(2))         // 100 * 2^1
        assertEquals(400L, policy.backoffDelay(3))         // 100 * 2^2
        assertEquals(12800L, policy.backoffDelay(8))       // 100 * 2^7
        assertEquals(102400L, policy.backoffDelay(11))     // 100 * 2^10
    }

    @Test
    fun `jitter zero with base one - the delay doubles per attempt`() {
        val policy = RetryPolicy(maxAttempts = 3, baseMs = 1, jitterMs = 0)
        assertEquals(1L, policy.backoffDelay(1))
        assertEquals(2L, policy.backoffDelay(2))
        assertEquals(4L, policy.backoffDelay(3))
        assertEquals(1024L, policy.backoffDelay(11))
    }

    @Test
    fun `jitter positive - the delay stays within the exponential plus jitter bounds`() {
        val policy = RetryPolicy(maxAttempts = 5, baseMs = 100, jitterMs = 50)
        repeat(50) { i ->
            val attempt = (i % 5) + 1
            val delay = policy.backoffDelay(attempt)
            val exponential = 100L shl (attempt - 1)
            assertTrue(
                delay >= exponential && delay < exponential + 50,
                "attempt $attempt delay $delay outside [$exponential, ${exponential + 50})",
            )
        }
    }

    @Test
    fun `attempt zero or negative is rejected fail-closed`() {
        val policy = RetryPolicy(maxAttempts = 3, baseMs = 100, jitterMs = 0)
        assertThrows(IllegalArgumentException::class.java) { policy.backoffDelay(0) }
        assertThrows(IllegalArgumentException::class.java) { policy.backoffDelay(-3) }
    }

    @Test
    fun `attempt one - the delay is the base plus bounded jitter`() {
        val policy = RetryPolicy(maxAttempts = 3, baseMs = 200, jitterMs = 100)
        repeat(20) {
            val delay = policy.backoffDelay(1)
            assertTrue(
                delay >= 200 && delay < 300,
                "attempt 1 delay $delay outside [200, 300)",
            )
        }
    }
}
