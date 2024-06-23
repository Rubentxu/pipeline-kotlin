package dev.rubentxu.pipeline.core.models.pipeline

import dev.rubentxu.pipeline.core.models.interfaces.PipelineModel

data class ToolsManagerDescriptor(
    val alternatives: AlternativesToolsDescriptor,
    val asdf: AsdfToolsManagerDescriptor
): PipelineModel {

    override fun toMap(): Map<String, Any> {
        return mapOf(
            "alternatives" to alternatives.toMap(),
            "asdf" to asdf.toMap()
        )
    }
}