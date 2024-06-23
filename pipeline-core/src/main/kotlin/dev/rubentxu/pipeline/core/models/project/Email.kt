package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel

data class Email(
    val id: String,
    val name: String,
    val description: String,
    val email: String,
    val notificatiosInteresteds: Set<Notification>
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "name" to name,
            "description" to description
        )
    }

}
