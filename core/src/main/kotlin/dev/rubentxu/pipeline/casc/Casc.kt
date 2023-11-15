package dev.rubentxu.pipeline.casc

data class PipelineConfig(
    val credentials: Credentials,
    val clouds: List<Cloud>,
)

data class Credentials(
    val system: SystemCredentials
)

data class SystemCredentials(
    val domainCredentials: List<DomainCredential>
)

data class DomainCredential(
    val credentials: List<Credential>
)

data class Cloud(
    val docker: DockerCloudConfig,
    val kubernetes: KubernetesConfig
)



