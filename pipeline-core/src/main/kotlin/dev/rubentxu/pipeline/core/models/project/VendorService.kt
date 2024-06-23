package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class VendorService(
    val id: String,
    val name: String,
    val baseUrl: String,
    val credentialsId: String,
    val metadata: Metadata
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "name" to name,
            "baseUrl" to baseUrl,
            "credentialsId" to credentialsId,
            "metadata" to metadata
        )
    }
}