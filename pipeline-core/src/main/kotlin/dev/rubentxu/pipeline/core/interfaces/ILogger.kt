package dev.rubentxu.pipeline.core.interfaces

import dev.rubentxu.pipeline.core.logger.LogConfigurationStrategy
import dev.rubentxu.pipeline.core.logger.LogLevel


interface ILogger : Configurable {

    fun setLogLevel(level: LogLevel)

    fun info(tag: String, message: String)

    fun warn(tag: String, message: String)

    fun debug(tag: String, message: String)

    fun error(tag: String, message: String)

    fun system(tag: String, message: String)

    fun <T> printPrettyLog(tag: String, level: LogLevel, obj: T)

    fun logPrettyMessages(tag: String, level: LogLevel, messages: List<String>)

    fun logPrettyError(tag: String, msgs: List<String>)

    fun changeConfigurationStrategy(logConfigurationStrategy: LogConfigurationStrategy)

}
