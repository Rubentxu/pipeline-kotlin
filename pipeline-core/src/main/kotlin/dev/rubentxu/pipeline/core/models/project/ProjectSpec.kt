package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel


data class ProjectSpec(
    var tools: List<ProjectTool>,
    var artifactsRepositories: List<ArtifactsRepository>,
    var deployTargets: List<DeployTarget>,
    var scannerTools: List<ScannerToolModel>,
    var vendorServices: List<VendorService>
) : ProjectDescriptorModel {
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