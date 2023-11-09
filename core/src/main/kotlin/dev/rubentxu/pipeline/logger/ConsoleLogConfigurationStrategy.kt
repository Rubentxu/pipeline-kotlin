package dev.rubentxu.pipeline.logger

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender

open class ConsoleLogConfigurationStrategy : LogConfigurationStrategy {
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
        consoleAppender.isImmediateFlush = true
        consoleAppender.start()

        rootLogger.level = Level.toLevel(logLevel.name)
        rootLogger.addAppender(consoleAppender)

        // Configuración específica para silenciar la librería Docker Java API
        val dockerJavaLogger = loggerContext.getLogger("com.github.dockerjava")
        dockerJavaLogger.level = Level.WARN // Establece el nivel de trazas deseado para Docker Java API
        dockerJavaLogger.addAppender(consoleAppender) // Si quieres que también muestre los WARN y ERROR en consola
        dockerJavaLogger.setAdditive(false) // Para evitar que los logs se propaguen al logger principal
    }
}