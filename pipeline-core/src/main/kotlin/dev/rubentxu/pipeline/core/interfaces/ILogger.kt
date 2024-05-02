package dev.rubentxu.pipeline.core.interfaces

import dev.rubentxu.pipeline.core.logger.LogLevel


interface ILogger : Configurable {

    fun setLogLevel(level: LogLevel)

    fun info(message: String)

    fun warn(message: String)

    fun debug(message: String)

    fun error(message: String)

    fun trace(message: String)

    fun <T> printPrettyLog(level: LogLevel, obj: T)

    fun logPrettyMessages(level: LogLevel, messages: List<String>)

    fun logPrettyError(msgs: List<String>)

}
