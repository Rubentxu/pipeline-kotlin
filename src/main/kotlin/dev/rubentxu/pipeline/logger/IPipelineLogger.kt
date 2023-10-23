package dev.rubentxu.pipeline.logger

interface IPipelineLogger {
    fun info(message: String)
    fun warn(message: String)
    fun debug(message: String)
    fun error(message: String)
    fun fatal(message: String)
    fun system(message: String)
}