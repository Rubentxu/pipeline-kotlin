package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class Project(
    val projectSettings: ProjectSettings,
    val sourceRepositories: List<SourceRepository>,
    val notifications: Notification,
    val spec: ProjectSpec,
    val status: ProjectStatus
) : ProjectModel {
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