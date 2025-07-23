package dev.rubentxu.pipeline.logger.interfaces

import dev.rubentxu.pipeline.logger.model.LogLevel

/**
 * An interface for logging messages. Methods are not suspend for ease of use.
 * The implementation will capture the context at creation time.
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

}