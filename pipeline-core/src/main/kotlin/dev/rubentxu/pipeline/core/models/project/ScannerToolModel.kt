package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class ScannerToolModel(
    val id: String,
    val name: String,
    val description: String,
    val command: String,
    val metadata: Metadata
) : ProjectModel {
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