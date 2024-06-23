package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class Notification(
    val emails: Set<Email>
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "emails" to emails.map { it.toMap() }
        )
    }
}