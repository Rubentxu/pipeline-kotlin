package dev.rubentxu.pipeline.casc

data class DockerCloudConfig(
    val name: String,
    val dockerApi: DockerApi,
    val templates: List<DockerTemplate>
)

data class DockerApi(
    val dockerHost: DockerHost
)

data class DockerHost(
    val uri: String
)

data class DockerTemplate(
    val labelString: String,
    val dockerTemplateBase: DockerTemplateBase,
    val remoteFs: String,
    val connector: Connector,
    val instanceCapStr: String,
    val retentionStrategy: RetentionStrategy
)

data class DockerTemplateBase(
    val image: String,
    val mounts: List<String>,
    val environmentsString: String
)

data class Connector(
    val attach: Attach
)

data class Attach(
    val user: String
)

data class RetentionStrategy(
    val idleMinutes: Int
)