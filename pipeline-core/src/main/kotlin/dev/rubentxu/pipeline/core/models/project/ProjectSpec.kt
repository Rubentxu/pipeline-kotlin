package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.interfaces.ProjectModel


data class ProjectSpec(
    val tools: List<ProjectTool>,
    val artifactsRepositories: List<ArtifactsRepository>,
    val deployTargets: List<DeployTarget>,
    val scannerTools: List<ScannerToolModel>,
    val vendorServices: List<VendorService>
) : ProjectModel {
    override fun toMap(): Map<String, Any> {
        return mapOf(
            "tools" to tools.map { it.toMap() },
            "artifactsRepositories" to artifactsRepositories.map { it.toMap() },
            "deployTargets" to deployTargets.map { it.toMap() },
            "scannerTools" to scannerTools.map { it.toMap() },
            "vendorServices" to vendorServices.map { it.toMap() }
        )
    }
}