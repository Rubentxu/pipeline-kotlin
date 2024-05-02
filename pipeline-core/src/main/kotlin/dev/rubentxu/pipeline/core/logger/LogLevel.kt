package dev.rubentxu.pipeline.core.logger


enum class LogLevel(val levelNumber: Int) {
    TRACE(1),
    DEBUG(2),
    INFO(3),
    WARN(4),
    ERROR(5) // must be in sync with JVM levels.
}

