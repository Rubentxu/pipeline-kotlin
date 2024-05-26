package dev.rubentxu.pipeline.core.logger

import ch.qos.logback.classic.LoggerContext

interface LogConfigurationStrategy {
    fun configure(loggerContext: LoggerContext, logLevel: LogLevel)
}