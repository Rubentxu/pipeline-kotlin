package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel


data class DeployTarget(
    var id: String,
    var name: String,
    var description: String,
    var type: String,
    var metadata: Metadata,
) : ProjectDescriptorModel {
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