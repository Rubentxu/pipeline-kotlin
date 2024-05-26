package dev.rubentxu.pipeline.core.logger

import ch.qos.logback.classic.LoggerContext
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.interfaces.IConfigClient
import dev.rubentxu.pipeline.core.interfaces.ILogger
import org.slf4j.LoggerFactory

@PipelineComponent
class Logger(
    private var logLevel: LogLevel= LogLevel.INFO,
    var logConfigurationStrategy: LogConfigurationStrategy = ConsoleLogConfigurationStrategy()
)  : ILogger {

    fun logMessage(level: LogLevel, message: String) {

    }

    private fun isLoggable(level: LogLevel): Boolean {
        return level.levelNumber <= logLevel.levelNumber
    }

    override fun setLogLevel(level: LogLevel) {
        TODO("Not yet implemented")
    }

    override fun info(tag: String, message: String) {
        logMessage(LogLevel.INFO, message)
    }

    override fun warn(tag: String, message: String) {
        logMessage(LogLevel.WARN, message)
    }

    override fun debug(tag: String, message: String) {
        logMessage(LogLevel.DEBUG, message)
    }

    override fun error(tag: String, message: String) {
        logMessage(LogLevel.ERROR, message)
    }

    override fun system(tag: String, message: String) {
        logMessage(LogLevel.SYSTEM, message)
    }

    fun executeWhenDebug(body: () -> Unit) {
        if (logLevel == LogLevel.DEBUG) {
            body()
        }
    }

    override fun <T> printPrettyLog(tag: String, level: LogLevel, obj: T) {
        if (isLoggable(level)) {
            logMessage(level, extendPrettyPrint(obj, 0, StringBuilder()).toString())
        }
    }

    override fun logPrettyMessages(tag: String, level: LogLevel, messages: List<String>) {
        logMessage(level, createPrettyMessage(messages))
    }

    override fun logPrettyError(tag: String, msgs: List<String>) {
        error(createPrettyMessage(msgs))
    }

    companion object {
        fun createPrettyMessage(msgs: List<String>): String {
            return listOf(
                "===========================================",
                flattenMessage(mutableListOf(), msgs.filter { it.isNotEmpty() }).joinToString("\n"),
                "==========================================="
            ).joinToString("\n")
        }

        protected fun flattenMessage(list: MutableList<String> = mutableListOf(), msgs: Any): List<String> {
            if (msgs !is String) {
                (msgs as? Iterable<*>)?.forEach { msg ->
                    flattenMessage(list, msg ?: return@forEach)
                }
            } else {
                list.add(msgs)
            }
            return list
        }
    }

    protected fun <T> extendPrettyPrint(obj: T, level: Int = 0, sb: StringBuilder = StringBuilder()): StringBuilder {
        val indent = { lev: Int -> sb.append("  ".repeat(lev)) }
        when (obj) {
            is Map<*, *> -> {
                sb.append("{\n")
                obj.forEach { (name, value) ->
                    indent(level + 1).append(name)
                    if (value is Map<*, *>) sb.append(" ") else sb.append(" = ")
                    extendPrettyPrint(value, level + 1, sb)
                    sb.append("\n")
                }
                indent(level).append("}")
            }

            is List<*> -> {
                sb.append("[\n")
                obj.forEachIndexed { index, value ->
                    indent(level + 1)
                    if (index < obj.size - 1) {
                        extendPrettyPrint(value, level + 1, sb).append(",")
                    } else {
                        extendPrettyPrint(value, level + 1, sb)
                    }
                    sb.append("\n")
                }
                indent(level).append("]")
            }

            is String -> {
                sb.append("\"").append(obj).append("\"")
            }

            else -> {
                sb.append(obj)
            }
        }
        return sb
    }

    override fun configure(configClient: IConfigClient) {
        val isDebugMode: Boolean = configClient.optional("projectDescriptor.debugMode", false)
        if (isDebugMode) {
            logLevel = LogLevel.DEBUG
        }
        logLevel = LogLevel.INFO
    }

    private fun configureLogger() {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        logConfigurationStrategy.configure(loggerContext, logLevel)
    }


    override fun changeConfigurationStrategy(logConfigurationStrategy: LogConfigurationStrategy) {
        this.logConfigurationStrategy = logConfigurationStrategy
        configureLogger()
    }

}
