package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel
import dev.rubentxu.pipeline.core.models.project.strategies.*


data class ProjectTool(
    var id: String,
    var name: String,
    var version: String,
    var cache: Boolean,
    var toolConfigurationFile: String,
    var artifactsRepositoryId: String,
    var strategies: List<ProjectActionStrategy>,
    var releaseStrategies: List<ReleaseStrategy>
) : ProjectDescriptorModel {
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
            "releaseStrategies" to releaseStrategies.map { it.toMap() },
            "buildStrategies" to buildStrategies


        )
    }

    fun getStrategyById(id: String): ProjectActionStrategy? {
        return strategies.find { it.id == id }
    }
}