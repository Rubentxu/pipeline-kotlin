package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class ProjectStatus(
    val artifacts: List<Artifact>,
    val projectConfigurationFiles: List<ProjectConfigurationFile>
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "artifacts" to artifacts.map { it.toMap() },
            "configurationFiles" to projectConfigurationFiles.map { it.toMap() }
        )
    }
}