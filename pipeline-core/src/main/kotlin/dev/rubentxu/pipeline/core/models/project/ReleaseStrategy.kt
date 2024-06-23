package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.project.strategies.ProjectActionStrategy

class ReleaseStrategy(
    id: String,
    name: String,
    description: String,
    metadata: Metadata,
) : ProjectActionStrategy(id, name, description, "", metadata) {

    override fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "name" to name,
            "description" to description,
            "metadata" to metadata
        )
    }

}
