package dev.rubentxu.pipeline.steps

interface Initializable {
    fun initialize(configuration: Map<String, Any>)
}