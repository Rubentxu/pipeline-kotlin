package dev.rubentxu.pipeline.core.interfaces


interface ITool : Configurable {

    fun execute(command: String): String

    fun execute(command: String, options: Map<String, Any>): String

}
