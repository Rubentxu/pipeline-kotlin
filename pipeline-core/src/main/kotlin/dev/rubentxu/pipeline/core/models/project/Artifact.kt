package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class Artifact(
    val id: String,
    val name: String,
    val version: String, // Calculated
    val url: String, // Calculated
    val localPath: String, // Calculated
    val description: String,
    val idRepository: String,
    val type: ArtifactType
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "name" to name,
            "version" to version,
            "url" to url,
            "localPath" to localPath,
            "description" to description,
            "idRepository" to idRepository,
            "type" to type.toMap()
        )
    }
}