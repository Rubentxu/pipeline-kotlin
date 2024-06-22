package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel


data class ProjectStatus(
    var artifacts: List<Artifact>,
    var projectConfigurationFiles: List<ProjectConfigurationFile>
) : ProjectDescriptorModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "artifacts" to artifacts.map { it.toMap() },
            "configurationFiles" to projectConfigurationFiles.map { it.toMap() }
        )
    }
}