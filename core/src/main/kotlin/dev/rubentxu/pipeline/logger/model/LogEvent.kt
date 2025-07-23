package dev.rubentxu.pipeline.logger.model

import dev.rubentxu.pipeline.logger.model.LogLevel
import java.time.Instant

enum class LogSource {
    LOGGER, STDOUT, STDERR
}

/**
 * Represents a single, structured, immutable log event.
 * This is the canonical data model for all logs in the system,
 * designed for efficient serialization and streaming.
 */
data class LogEvent(
    val timestamp: Instant,
    val level: LogLevel,
    val loggerName: String,
    val message: String,
    val correlationId: String?,
    val contextData: Map<String, String>,
    val exception: Throwable? = null,
    val source: LogSource = LogSource.LOGGER
)