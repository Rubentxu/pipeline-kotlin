package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel


data class ArtifactType(
    var name: String,
    var extension: String
) : ProjectDescriptorModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "name" to name,
            "extension" to extension
        )
    }
}