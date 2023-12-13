package dev.rubentxu.pipeline.model.logger

import ch.qos.logback.classic.LoggerContext

interface LogConfigurationStrategy {
    fun configure(loggerContext: LoggerContext, logLevel: LogLevel)
}