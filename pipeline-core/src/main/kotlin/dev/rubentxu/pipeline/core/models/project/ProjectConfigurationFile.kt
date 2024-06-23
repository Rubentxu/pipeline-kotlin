package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel

data class ProjectConfigurationFile(
    val name: String,
    val version: String,
    val dependencies: Any,
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "name" to name,
            "version" to version,
            "dependencies" to dependencies
        )
    }
}