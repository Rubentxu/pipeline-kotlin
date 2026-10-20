package dev.rubentxu.pipeline.v2.domain.durable

import java.time.Instant

/**
 * Port for obtaining the current instant.
 *
 * F-ARCH-001 compliant: only uses `java.time.Instant`;
 * does not import `java.time.Clock`.
 *
 */
interface Clock {
    /**
     * Returns the current instant.
     *
     * @return The current point in time.
     */
    fun now(): Instant
}
