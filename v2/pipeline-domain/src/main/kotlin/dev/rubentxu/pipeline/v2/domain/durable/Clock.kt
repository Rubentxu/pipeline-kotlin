package dev.rubentxu.pipeline.v2.domain.durable

import java.time.Instant

/**
 * Port for obtaining the current instant.
 *
 * F-ARCH-001 compliant: only uses `java.time.Instant`;
 * does not import `java.time.Clock`.
 *
 * @see <a href="design.md §E4-09">Design §E4-09</a>
 */
interface Clock {
    /**
     * Returns the current instant.
     *
     * @return The current point in time.
     */
    fun now(): Instant
}
