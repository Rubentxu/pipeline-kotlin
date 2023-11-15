package dev.rubentxu.pipeline.logger

import ch.qos.logback.classic.LoggerContext
import org.slf4j.Logger

import org.slf4j.LoggerFactory

/**
 * Provides logging capabilities for a pipeline, with support for different log levels and color-coded output.
 *
 * @property logLevel The current log level for this logger. Only messages with this level or higher will be logged.
 * @constructor Creates a new logger with the specified log level.
 */
class PipelineLogger(
    private var logLevel: LogLevel,
    var logConfigurationStrategy: LogConfigurationStrategy = ConsoleLogConfigurationStrategy()
) : IPipelineLogger {

    private val logger = LoggerFactory.getLogger(PipelineLogger::class.java)

    init {
        configureLogger()
    }

    private fun configureLogger() {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        logConfigurationStrategy.configure(loggerContext, logLevel)
    }

    override fun changeLogLevel(logLevel: LogLevel) {
        this.logLevel = logLevel
        configureLogger()
    }

    override fun changeConfigurationStrategy(logConfigurationStrategy: LogConfigurationStrategy) {
        this.logConfigurationStrategy = logConfigurationStrategy
        configureLogger()
    }

    /**
     * Stores the logged messages.
     */
    private val logs = mutableListOf<String>()

    private val RESET = "\u001B[0m"
    private val RED = "\u001B[31m"
    private val YELLOW = "\u001B[33m"
    private val GREEN = "\u001B[32m"
    private val MAGENTA = "\u001B[35m"
    private val CYAN = "\u001B[36m"
    private val BOLD = "\u001B[1m"
    private val ITALIC = "\u001B[3m"


    init {
        setLogLevel(logLevel)
    }

    /**
     * Sets the log level for this logger.
     *
     * @param level The new log level.
     */
    fun setLogLevel(level: LogLevel) {
        logLevel = level
    }

    /**
     * Logs a message with the specified level.
     *
     * @param level   The level for this log message.
     * @param message The message to log.
     */
    private fun log(level: LogLevel, message: String) {
        val format = LogFormat.Builder(text = message)
            .level(level)
            .text(message)



        if (level.value <= logLevel.value) {
            when (level) {
                LogLevel.ERROR -> {
                    format.color(RED)
                    format.style(BOLD)

                }

                LogLevel.WARN -> {
                    format.color(YELLOW)
                    format.style(BOLD)
                }

                LogLevel.INFO -> {
                    format.color(GREEN)
                }

                LogLevel.DEBUG -> {
                    format.color(MAGENTA)
                    format.style(ITALIC)
                }

                LogLevel.TRACE -> {
                    format.color(CYAN)
                    format.style(ITALIC)
                }

                LogLevel.QUIET -> format.color(GREEN)
            }
            write(format.build())
        }
    }

    private fun write(formatOpts: LogFormat) {
        val msg = formatMessage(formatOpts)
        logs.add(msg)
        when (formatOpts.level) {
            LogLevel.ERROR -> logger.error(msg)
            LogLevel.WARN -> logger.warn(msg)
            LogLevel.INFO -> logger.info(msg)
            LogLevel.DEBUG -> logger.debug(msg)
            LogLevel.TRACE -> logger.trace(msg)
            LogLevel.QUIET -> {}
        }
    }

    private fun formatMessage(options: LogFormat): String {
        return "${options.color}${options.style} ${options.text}${options.reset}"
    }

    /**
     * Logs an informational message.
     *
     * @param message The message to log.
     */
    override fun info(message: String) {
        log(LogLevel.INFO, message)
    }

    /**
     * Logs a warning message.
     *
     * @param message The message to log.
     */
    override fun warn(message: String) {
        log(LogLevel.WARN, message)
    }

    /**
     * Logs a debug message.
     *
     * @param message The message to log.
     */
    override fun debug(message: String) {
        log(LogLevel.DEBUG, message)
    }

    /**
     * Logs an error message.
     *
     * @param message The message to log.
     */
    override fun error(message: String) {
        log(LogLevel.ERROR, message)
    }

    /**
     * Logs a system message.
     *
     * @param message The message to log.
     */
    override fun system(message: String) {
        log(LogLevel.TRACE, message)
    }

    override fun logs(): List<String> {

        return logs
    }

    /**
     * Executes the specified block only if the current log level is DEBUG.
     *
     * @param body The block to execute.
     */
    fun whenDebug(body: () -> Unit) {
        if (logLevel == LogLevel.DEBUG) {
            body()
        }
    }

    /**
     * Pretty-prints an object at the specified log level.
     *
     * @param levelLog The level for this log message.
     * @param obj      The object to log.
     */
    fun <T> prettyPrint(levelLog: LogLevel, obj: T) {
        log(levelLog, prettyPrintExtend(obj))
    }

    private fun <T> prettyPrintExtend(obj: T, level: Int = 0, sb: StringBuilder = StringBuilder()): String {
        fun indent(lev: Int) = sb.append("  ".repeat(lev))

        when (obj) {
            is Map<*, *> -> {
                sb.append("{\n")
                obj.forEach { (name, value) ->
                    indent(level + 1).append(name)
                    if (value is Map<*, *>) {
                        sb.append(" ")
                    } else {
                        sb.append(" = ")
                    }
                    sb.append(prettyPrintExtend(value, level + 1)).append("\n")
                }
                indent(level).append("}")
            }

            is List<*> -> {
                sb.append("[\n")
                obj.forEachIndexed { index, value ->
                    indent(level + 1)
                    sb.append(prettyPrintExtend(value, level + 1))
                    if (index != obj.size - 1) {
                        sb.append(",")
                    }
                    sb.append("\n")
                }
                indent(level).append("]")
            }

            is String -> sb.append('"').append(obj).append('"')
            else -> sb.append(obj)
        }
        return sb.toString()
    }

    /**
     * Logs a banner with the specified messages at the specified log level.
     *
     * @param level    The level for this log message.
     * @param messages The messages to include in the banner.
     */
    fun echoBanner(level: LogLevel, messages: List<String>) {
        log(level, createBanner(messages))
    }

    /**
     * Logs a banner with the specified messages at the ERROR log level.
     *
     * @param msgs The messages to include in the banner.
     */
    fun errorBanner(msgs: List<String>) {
        error(createBanner(msgs))
    }

    private fun createBanner(msgs: List<String>): String {
        val messageLines = msgFlatten(mutableListOf(), msgs.filter { it.isNotEmpty() })
        // Obtener la longitud de la línea más larga
        val maxLength = messageLines.maxOf { it.length }
        // Crear la línea de '=' multiplicada
        val separator = "=".repeat(maxLength+7)
        return """
        |
        |   $separator
        |       ${messageLines.joinToString("\n       ")} 
        |   $separator
        |   
        """.trimMargin()
    }

    private fun msgFlatten(list: MutableList<String>, msgs: Any): List<String> {
        when (msgs) {
            is String -> list.add(msgs)
            is List<*> -> msgs.forEach { msg -> msgFlatten(list, msg ?: "") }
        }
        return list
    }


    // create static getLogger singleton method property static
    companion object {
        private var logger: IPipelineLogger? = null

        fun getLogger(): IPipelineLogger {
            if (logger == null) {
                logger = PipelineLogger(LogLevel.INFO)
            }
            return logger!!
        }
    }
}