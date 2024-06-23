package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class SourceRepository(
    val id: String,
    val url: String,
    val branch: String,
    val email: String,
    val userName: String,
    val credentialsId: String
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "url" to url,
            "branch" to branch,
            "email" to email,
            "userName" to userName,
            "credentialsId" to credentialsId
        )
    }
}