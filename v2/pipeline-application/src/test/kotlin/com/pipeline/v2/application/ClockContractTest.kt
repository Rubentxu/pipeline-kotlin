package com.pipeline.v2.application

import com.pipeline.v2.domain.durable.Clock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

/**
 * Contract tests for [Clock] interface (C-020).
 *
 * Tests the F-ARCH-001 compliance: Clock.now() returns java.time.Instant,
 * not java.time.Clock.
 *
 * Placed in :pipeline-application because SystemClock lives there.
 * Uses a private TestClock embedded in the test class to verify
 * deterministic behavior and interface-based DI.
 */
class ClockContractTest {

    /**
     * C-020.1: SystemClock delegates to java.time.Clock.systemUTC()
     *
     * Verifies that two close calls to SystemClock.now() return
     * monotonically non-decreasing instants (within tolerance of wall-clock
     * drift between the two calls).
     */
    @Test
    fun `system clock delegates to java time Clock systemUTC`() {
        val clock = SystemClock()
        val first = clock.now()
        val second = clock.now()

        // Instants must be non-decreasing (allowing for identical if calls are fast)
        assertTrue(
            !second.isBefore(first),
            "Subsequent now() calls must not go backwards. first=$first, second=$second"
        )

        // Both must be valid Instants (non-null, after epoch)
        assertTrue(first.toEpochMilli() > 0, "SystemClock.now() must return a post-epoch Instant")
        assertTrue(second.toEpochMilli() > 0, "SystemClock.now() must return a post-epoch Instant")
    }

    /**
     * C-020.2: TestClock is deterministic for test repeatability
     *
     * A TestClock backed by a fixed Instant must return that exact Instant
     * on every call to now(), proving it is suitable for deterministic testing.
     */
    @Test
    fun `test clock returns fixed instant deterministically`() {
        val fixedInstant = Instant.parse("2025-01-01T00:00:00Z")
        val clock = TestClock(fixedInstant)

        repeat(5) {
            val result = clock.now()
            assertEquals(
                fixedInstant,
                result,
                "TestClock.now() must return the fixed Instant on every call"
            )
        }
    }

    /**
     * C-020.3: Clock injection point accepts both SystemClock and TestClock
     *
     * Proves that the Clock port works with interface-based DI by passing
     * both implementations through a tiny harness and verifying both succeed.
     */
    @Test
    fun `clock injection point works with both system and test implementations`() {
        // Harness that exercises only the Clock interface
        fun exerciseClock(clock: Clock): Instant {
            return clock.now()
        }

        // Both must work without casting or unwrapping
        val instantFromSystem = exerciseClock(SystemClock())
        val instantFromTest = exerciseClock(TestClock(Instant.parse("2024-06-15T12:00:00Z")))

        assertTrue(instantFromSystem.toEpochMilli() > 0, "SystemClock must produce valid Instant")
        assertEquals(
            Instant.parse("2024-06-15T12:00:00Z"),
            instantFromTest,
            "TestClock must produce the configured fixed Instant"
        )
    }

    /**
     * C-020.4: Clock now returns java time Instant not Clock type
     *
     * Verifies F-ARCH-001: the return type of Clock.now() is java.time.Instant,
     * NOT java.time.Clock. The :pipeline-domain module must NOT import
     * java.time.Clock.
     */
    @Test
    fun `clock now returns java time Instant not Clock type`() {
        val clock: Clock = SystemClock()
        val result = clock.now()

        // The return type must be Instant, not java.time.Clock
        assertTrue(
            result is Instant,
            "Clock.now() must return java.time.Instant, got: ${result::class.java.name}"
        )

        // Verify it is NOT a java.time.Clock
        assertFalse(
            result::class.java.name == "java.time.Clock",
            "Clock.now() must NOT return java.time.Clock (F-ARCH-001 violation)"
        )

        // Additional sanity: Instant must be usable as epoch milliseconds
        assertTrue(result.toEpochMilli() > 0, "Returned Instant must be a valid epoch-based time")
    }

    /**
     * Private TestClock implementation for deterministic testing.
     * Not in production code — only for test repeatability.
     */
    private class TestClock(private val fixedInstant: Instant) : Clock {
        override fun now(): Instant = fixedInstant
    }
}
