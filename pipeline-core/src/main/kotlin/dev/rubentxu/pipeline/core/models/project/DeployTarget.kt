package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class DeployTarget(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val metadata: Metadata,
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "name" to name,
            "description" to description,
            "type" to type,
            "metadata" to metadata
        )
    }
}