package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.validation.validateAndGet

data class DockerCloudConfig(
    val name: String,
    val dockerHost: String,
    val templates: List<DockerTemplate>
): Configuration {
    companion object: MapConfigurationBuilder<DockerCloudConfig> {

        override fun build(data: Map<String, Any>): DockerCloudConfig? {
            val templatesMap = data.validateAndGet("docker.templates")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val templates: List<DockerTemplate> = templatesMap.map {
                return@map DockerTemplate.build(it)
            }

            return DockerCloudConfig(
                name = data.validateAndGet("docker.name").isString().throwIfInvalid("name is required in DockerCloudConfig"),
                dockerHost = data.validateAndGet("docker.dockerApi.dockerHost.uri").isString().throwIfInvalid("dockerHost is required in DockerCloudConfig"),
                templates = templates,
            )
        }
    }
}


data class DockerTemplate(
    val labelString: String,
    val dockerTemplateBase: DockerTemplateBase,
    val remoteFs: String,
    val user: String,
    val instanceCapStr: String,
    val retentionStrategy: RetentionStrategy
): Configuration {
    companion object: MapConfigurationBuilder<DockerTemplate> {
        override fun build(data: Map<String, Any>): DockerTemplate {
            val templateBaseMap: Map<String, Any> = data.validateAndGet("dockerTemplateBase")
                .isMap()
                .throwIfInvalid("dockerTemplateBase is required in DockerTemplate") as Map<String, Any>


            return DockerTemplate(
                labelString = data.validateAndGet("labelString").isString().throwIfInvalid("labelString is required in DockerTemplate"),
                dockerTemplateBase = DockerTemplateBase.build(templateBaseMap),
                remoteFs = data.validateAndGet("remoteFs").isString().throwIfInvalid("remoteFs is required in DockerTemplate"),
                user = data.validateAndGet("connector.attach.user").isString().throwIfInvalid("connector.attach.user is required in DockerTemplate"),
                instanceCapStr = data.validateAndGet("instanceCapStr").isString().throwIfInvalid("instanceCapStr is required in DockerTemplate"),
                retentionStrategy = RetentionStrategy.build(data)
            )
        }
    }
}

data class DockerTemplateBase(
    val image: String,
    val mounts: List<String>,
    val environmentsString: String
): Configuration {
    companion object: MapConfigurationBuilder<DockerTemplateBase> {
        override fun build(data: Map<String, Any>): DockerTemplateBase {
            return DockerTemplateBase(
                image = data.validateAndGet("image").isString().throwIfInvalid("image is required in DockerTemplateBase"),
                mounts = data.validateAndGet("mounts").isList().defaultValueIfInvalid(emptyList<String>()) as List<String>,
                environmentsString = data.validateAndGet("environmentsString").isString().throwIfInvalid("environmentsString is required in DockerTemplateBase")
            )
        }
    }
}


data class RetentionStrategy(
    val idleMinutes: Int
): Configuration {
    companion object: MapConfigurationBuilder<RetentionStrategy> {
        override fun build(data: Map<String, Any>): RetentionStrategy {
            return RetentionStrategy(
                idleMinutes = data.validateAndGet("retentionStrategy.idleMinutes").isNumber().throwIfInvalid() as Int
            )
        }
    }
}