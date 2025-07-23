package dev.rubentxu.pipeline.logger

import dev.rubentxu.pipeline.logger.model.LogEvent
import dev.rubentxu.pipeline.logger.model.LogLevel

/**
 * A utility class responsible for formatting LogEvent objects into human-readable strings,
 * with support for colors and structured data pretty-printing.
 */
object ConsoleLogFormatter {

    private const val RESET = "\u001B[0m"
    private const val RED = "\u001B[31m"
    private const val YELLOW = "\u001B[33m"
    private const val GREEN = "\u001B[32m"
    private const val MAGENTA = "\u001B[35m"
    private const val CYAN = "\u001B[36m"
    private const val BOLD = "\u001B[1m"
    private const val ITALIC = "\u001B[3m"

    fun format(event: LogEvent): String {
        val color = getColorFor(event.level)
        val style = getStyleFor(event.level)
        val contextString = formatContext(event.contextData)

        val message = if (event.exception != null) {
            "${event.message}\n${event.exception.stackTraceToString()}"
        } else {
            event.message
        }

        return "$style$color[${event.loggerName}] $message$contextString$RESET"
    }

    /**
     * Formats a log event without color codes for plain text output.
     * Useful for file logging or environments that don't support ANSI colors.
     */
    fun formatPlain(event: LogEvent): String {
        val timestamp = event.timestamp.toString()
        val level = event.level.name.padEnd(5)
        val contextString = formatContextPlain(event.contextData)

        val message = if (event.exception != null) {
            "${event.message}\n${event.exception.stackTraceToString()}"
        } else {
            event.message
        }

        val correlationPart = event.correlationId?.let { " [$it]" } ?: ""
        return "$timestamp $level [${event.loggerName}]$correlationPart $message$contextString"
    }

    private fun formatContext(context: Map<String, String>): String {
        if (context.isEmpty()) return ""
        return "\n" + prettyPrint(context)
    }

    private fun formatContextPlain(context: Map<String, String>): String {
        if (context.isEmpty()) return ""
        return " " + context.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    private fun getColorFor(level: LogLevel): String = when (level) {
        LogLevel.ERROR -> RED
        LogLevel.WARN -> YELLOW
        LogLevel.INFO -> GREEN
        LogLevel.DEBUG -> MAGENTA
        LogLevel.TRACE -> CYAN
        LogLevel.QUIET -> ""
    }

    private fun getStyleFor(level: LogLevel): String = when (level) {
        LogLevel.ERROR, LogLevel.WARN -> BOLD
        LogLevel.DEBUG, LogLevel.TRACE -> ITALIC
        else -> ""
    }

    private fun <T> prettyPrint(obj: T, level: Int = 0, sb: StringBuilder = StringBuilder()): String {
        fun indent(lev: Int) = sb.append("  ".repeat(lev))
        when (obj) {
            is Map<*, *> -> {
                sb.append("{\n")
                obj.forEach { (name, value) ->
                    indent(level + 1).append(name).append(" = ").append(prettyPrint(value, level + 1)).append("\n")
                }
                indent(level).append("}")
            }
            is List<*> -> {
                sb.append("[\n")
                obj.forEachIndexed { index, value ->
                    indent(level + 1)
                    sb.append(prettyPrint(value, level + 1))
                    if (index != obj.size - 1) sb.append(",")
                    sb.append("\n")
                }
                indent(level).append("]")
            }
            is String -> sb.append('"').append(obj).append('"')
            else -> sb.append(obj)
        }
        return sb.toString()
    }
}