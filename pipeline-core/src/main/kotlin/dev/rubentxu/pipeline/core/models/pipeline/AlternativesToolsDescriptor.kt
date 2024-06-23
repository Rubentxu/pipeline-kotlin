package dev.rubentxu.pipeline.core.models.pipeline

import dev.rubentxu.pipeline.core.models.interfaces.PipelineModel


data class AlternativesToolsDescriptor(
    val javaEngine: String = "java17"
) : PipelineModel {

    override fun toMap(): Map<String, Any> {
        return mapOf(
            "javaEngine" to javaEngine
        )
    }
}