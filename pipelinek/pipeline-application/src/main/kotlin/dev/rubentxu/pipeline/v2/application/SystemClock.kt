package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import java.time.Instant

/**
 * System clock implementation using java.time.Clock.systemUTC().
 *
 * @see Clock
 */
class SystemClock : Clock {
    override fun now(): Instant = java.time.Clock.systemUTC().instant()
}
