package dev.rubentxu.pipeline.core.models.pipeline

import dev.rubentxu.pipeline.core.models.interfaces.PipelineModel

data class PipelineDefinition(
    val settings: PipelineSettings,
    val cache: CacheDescriptor,
    val toolsManager: ToolsManagerDescriptor
) : PipelineModel {

    override fun toMap(): Map<String, Any> {
        return mapOf(
            "apiVersion" to settings.apiVersion,
            "skipStages" to settings.skipStages,
            "debugMode" to settings.debugMode,
            "devopsEnvironment" to settings.devopsEnvironment,
            "cache" to cache.toMap(),
            "toolsManager" to toolsManager.toMap(),
            "environmentVars" to settings.environmentVars
        )
    }
}