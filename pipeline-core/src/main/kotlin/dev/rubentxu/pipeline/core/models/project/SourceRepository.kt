package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel


data class SourceRepository(
    var id: String,
    var url: String,
    var branch: String,
    var email: String,
    var userName: String,
    var credentialsId: String
) : ProjectDescriptorModel {
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