package dev.rubentxu.pipeline.model.logger


enum class LogLevel(val value: Int) {
    DEBUG(500),
    INFO(400),
    TRACE(600),
    WARN(300),
    ERROR(200),
    QUIET(100),

}
