package dev.rubentxu.pipeline.logger

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender

class ConsoleLogConfigurationStrategy : LogConfigurationStrategy {
    override fun configure(loggerContext: LoggerContext, logLevel: LogLevel) {
        // Clear any existing appenders on the root logger
        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.detachAndStopAllAppenders()

        val encoder = PatternLayoutEncoder()
        encoder.context = loggerContext
        encoder.pattern = "%date{HH:mm:ss.SSS} [%level] %msg%n"
        encoder.start()

        val consoleAppender = ConsoleAppender<ILoggingEvent>()
        consoleAppender.context = loggerContext
        consoleAppender.encoder = encoder
        consoleAppender.start()

        rootLogger.level = Level.toLevel(logLevel.name)
        rootLogger.addAppender(consoleAppender)
    }
}