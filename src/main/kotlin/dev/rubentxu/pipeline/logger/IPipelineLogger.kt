package dev.rubentxu.pipeline.logger

/**
 * An interface for logging messages from a pipeline, supporting different log levels.
 */
interface IPipelineLogger {

    /**
     * Logs an informational message.
     *
     * @param message The message to log.
     */
    fun info(message: String)

    /**
     * Logs a warning message.
     *
     * @param message The message to log.
     */
    fun warn(message: String)

    /**
     * Logs a debug message.
     *
     * @param message The message to log.
     */
    fun debug(message: String)

    /**
     * Logs an error message.
     *
     * @param message The message to log.
     */
    fun error(message: String)

    /**
     * Logs a fatal error message.
     *
     * @param message The message to log.
     */
    fun fatal(message: String)

    /**
     * Logs a system message.
     *
     * @param message The message to log.
     */
    fun system(message: String)
}