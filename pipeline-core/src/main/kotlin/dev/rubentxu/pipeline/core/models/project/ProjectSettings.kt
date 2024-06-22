package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel


data class ProjectSettings(
    var apiVersion: String = "v1",
    var name: String,
    var description: String,
    var inceptionYear: String,
    var developCenter: String,
    var codeCapp: String,
    var elementoPromocionable: String,
    var version: String,
) : ProjectDescriptorModel {

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