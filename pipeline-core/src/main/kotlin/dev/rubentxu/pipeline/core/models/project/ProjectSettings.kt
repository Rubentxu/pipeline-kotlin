package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class ProjectSettings(
    val apiVersion: String = "v1",
    val name: String,
    val description: String,
    val inceptionYear: String,
    val developCenter: String,
    val codeCapp: String,
    val elementoPromocionable: String,
    val version: String,
) : ProjectModel {

    override fun toMap(): Map<String, Any> {
        return mapOf(
            "apiVersion" to apiVersion,
            "name" to name,
            "description" to description,
            "inceptionYear" to inceptionYear,
            "developCenter" to developCenter,
            "codeCapp" to codeCapp,
            "elementoPromocionable" to elementoPromocionable,
            "version" to version
        )
    }
}