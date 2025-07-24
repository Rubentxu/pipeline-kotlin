package dev.rubentxu.pipeline.logger.interfaces

import dev.rubentxu.pipeline.logger.model.LogLevel

/**
 * An interface for logging messages. Methods are regular functions that can be used anywhere.
 * LoggingContext (correlation IDs, user IDs, etc.) will be automatically captured when available from coroutine context.
 */
interface ILogger {
    val name: String
    
    fun info(message: String, data: Map<String, String> = emptyMap())
    fun warn(message: String, data: Map<String, String> = emptyMap())
    fun debug(message: String, data: Map<String, String> = emptyMap())
    fun error(message: String, throwable: Throwable? = null, data: Map<String, String> = emptyMap())
    fun trace(message: String, data: Map<String, String> = emptyMap())
    fun critical(message: String, data: Map<String, String> = emptyMap())

    /**
     * Logs a system message, typically for internal framework events.
     */
    fun system(message: String)
    
    /**
     * Generic log method
     */
    fun log(level: LogLevel, message: String, exception: Throwable? = null)
    fun log(level: LogLevel, message: String, contextData: Map<String, String>, exception: Throwable? = null)

    /**
     * Pretty-prints an object at the specified log level.
     *
     * @param level The level for this log message.
     * @param obj The object to log.
     */
    fun <T> prettyPrint(level: LogLevel, obj: T)

    /**
     * Logs a banner with the specified messages at the ERROR log level.
     *
     * @param messages The messages to include in the banner.
     */
    fun errorBanner(messages: List<String>)

}