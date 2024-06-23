package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel

data class ArtifactsRepository(
    val id: String,
    val name: String,
    val url: String,
    val type: String,
    val credentialsId: String,
    val metadata: Metadata
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "name" to name,
            "url" to url,
            "type" to type,
            "credentialsId" to credentialsId,
            "metadata" to metadata
        )
    }
}