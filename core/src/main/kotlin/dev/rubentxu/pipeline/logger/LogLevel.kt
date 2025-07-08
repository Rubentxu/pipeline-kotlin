package dev.rubentxu.pipeline.logger

/**
 * Represents the log level for pipeline logging.
 *
 * @property value The numeric value representing the log level priority.
 */
enum class LogLevel(val value: Int) {
    /** Debug level (lowest priority for verbose output). */
    DEBUG(500),

    /** Info level (default informational messages). */
    INFO(400),

    /** Trace level (most verbose output). */
    TRACE(600),

    /** Warning level (potential issues). */
    WARN(300),

    /** Error level (serious issues). */
    ERROR(200),

    /** Quiet level (minimal output). */
    QUIET(100),
}
