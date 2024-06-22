package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel


data class ProjectDescriptor(
    var projectSettings: ProjectSettings,
    var sourceRepositories: List<SourceRepository>,
    var notifications: Notification,
    var spec: ProjectSpec,
    var status: ProjectStatus
) : ProjectDescriptorModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "apiVersion" to projectSettings.apiVersion,
            "name" to projectSettings.name,
            "description" to projectSettings.description,
            "inceptionYear" to projectSettings.inceptionYear,
            "developCenter" to projectSettings.developCenter,
            "codeCapp" to projectSettings.codeCapp,
            "elementoPromocionable" to projectSettings.elementoPromocionable,
            "version" to projectSettings.version,
            "sourceRepositories" to sourceRepositories.map { it.toMap() },
            "notifications" to notifications.toMap(),
            "spec" to spec.toMap(),
            "status" to status.toMap()
        )
    }
}