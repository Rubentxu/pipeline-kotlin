package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel
import dev.rubentxu.pipeline.core.models.project.strategies.*


data class ProjectTool(
    val id: String,
    val name: String,
    val version: String,
    val cache: Boolean,
    val toolConfigurationFile: String,
    val artifactsRepositoryId: String,
    val strategies: List<ProjectActionStrategy>,
    val releaseStrategies: List<ReleaseStrategy>
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        val buildStrategies: Map<String, Any> = strategies.find { it is BuildStrategy }.let { (it as BuildStrategy?)?.toMap() }?: emptyMap()
        val testStrategies: Map<String, Any> = strategies.find { it is TestStrategy }.let { (it as TestStrategy?)?.toMap() }?: emptyMap()
        val deployStrategies: Map<String, Any> = strategies.find { it is DeployStrategy }.let { (it as DeployStrategy?)?.toMap() }?: emptyMap()
        val publishStrategies: Map<String, Any> = strategies.find { it is PublishStrategy }.let { (it as PublishStrategy?)?.toMap() }?: emptyMap()
        val undeployStrategies: Map<String, Any> = strategies.find { it is UndeployStrategy }.let { (it as UndeployStrategy?)?.toMap() }?: emptyMap()
        val rollbackStrategies: Map<String, Any> = strategies.find { it is RollbackStrategy }.let { (it as RollbackStrategy?)?.toMap() }?: emptyMap()
        val cleanupStrategies: Map<String, Any> = strategies.find { it is CleanupStrategy }.let { (it as CleanupStrategy?)?.toMap() }?: emptyMap()
        val releaseStrategies: Map<String, Any> = strategies.find { it is ReleaseStrategy }.let { (it as ReleaseStrategy?)?.toMap() }?: emptyMap()



        return mapOf(
            "id" to id,
            "name" to name,
            "version" to version,
            "cache" to cache,
            "toolConfigurationFile" to toolConfigurationFile,
            "artifactsRepositoryId" to artifactsRepositoryId,
            "releaseStrategies" to releaseStrategies,
            "buildStrategies" to buildStrategies,
            "testStrategies" to testStrategies,
            "deployStrategies" to deployStrategies,
            "publishStrategies" to publishStrategies,
            "undeployStrategies" to undeployStrategies,
            "rollbackStrategies" to rollbackStrategies,
            "cleanupStrategies" to cleanupStrategies


        )
    }

    fun getStrategyById(id: String): ProjectActionStrategy? {
        return strategies.find { it.id == id }
    }
}