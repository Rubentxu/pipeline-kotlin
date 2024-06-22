package dev.rubentxu.pipeline.core.models.project.strategies

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel


open class ProjectActionStrategy(
    var id: String,
    var name: String,
    var description: String,
    var command: String,
    var metadata: Metadata
) : ProjectDescriptorModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "name" to name,
            "description" to description,
            "command" to command,
            "metadata" to metadata
        )
    }
}