package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class ArtifactType(
    val name: String,
    val extension: String
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "name" to name,
            "extension" to extension
        )
    }
}