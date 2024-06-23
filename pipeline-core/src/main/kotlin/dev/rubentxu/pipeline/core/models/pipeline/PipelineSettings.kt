package dev.rubentxu.pipeline.core.models.pipeline

import dev.rubentxu.pipeline.core.models.interfaces.PipelineModel


data class PipelineSettings(
    val apiVersion: String = "v1",
    val skipStages: Set<String> = emptySet(),
    val debugMode: Boolean = false,
    val devopsEnvironment: String,
    val environmentVars: EnvVars = EnvVars(emptyMap())

) : PipelineModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "apiVersion" to apiVersion,
            "skipStages" to skipStages,
            "debugMode" to debugMode,
            "devopsEnvironment" to devopsEnvironment,
            "environmentVars" to environmentVars
        )
    }
}