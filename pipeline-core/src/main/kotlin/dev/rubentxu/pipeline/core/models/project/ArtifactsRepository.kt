package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel

data class ArtifactsRepository(
    var id: String,
    var name: String,
    var url: String,
    var type: String,
    var credentialsId: String,
    var metadata: Metadata
) : ProjectDescriptorModel {
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