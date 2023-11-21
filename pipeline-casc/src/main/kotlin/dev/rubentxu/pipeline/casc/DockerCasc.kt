package dev.rubentxu.pipeline.casc

import dev.rubentxu.pipeline.validation.validateAndGet

data class DockerCloudConfig(
    val name: String,
    val dockerHost: String,
    val templates: List<DockerTemplate>
) {
    companion object {

        fun fromMap(map: Map<String, Any>): DockerCloudConfig {
           val templatesMap = map.validateAndGet("templates").isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>
           val templates: List<DockerTemplate> = templatesMap.map {
               return@map DockerTemplate.fromMap(it)
           }

           return DockerCloudConfig(
               name = map.validateAndGet("name").isString().throwIfInvalid("name is required in DockerCloudConfig"),
               dockerHost = map.validateAndGet("dockerHost").isString().throwIfInvalid("dockerHost is required in DockerCloudConfig"),
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
) {
    companion object {
        fun fromMap(it: Map<String, Any>): DockerTemplate {
            val templateBaseMap: Map<String, Any> = it.validateAndGet("dockerTemplateBase").isMap().throwIfInvalid("dockerTemplateBase is required in DockerTemplate") as Map<String, Any>


            return DockerTemplate(
                labelString = it.validateAndGet("labelString").isString().throwIfInvalid("labelString is required in DockerTemplate"),
                dockerTemplateBase = DockerTemplateBase.fromMap(templateBaseMap),
                remoteFs = it.validateAndGet("remoteFs").isString().throwIfInvalid("remoteFs is required in DockerTemplate"),
                user = it.validateAndGet("connector.attach.user").isString().throwIfInvalid("connector.attach.user is required in DockerTemplate"),
                instanceCapStr = it.validateAndGet("instanceCapStr").isString().throwIfInvalid("instanceCapStr is required in DockerTemplate"),
                retentionStrategy = RetentionStrategy.fromMap(it)
            )
        }
    }
}

data class DockerTemplateBase(
    val image: String,
    val mounts: List<String>,
    val environmentsString: String
) {
    companion object {
        fun fromMap(templateBaseMap: Map<String, Any>): DockerTemplateBase {
            return DockerTemplateBase(
                image = templateBaseMap.validateAndGet("image").isString().throwIfInvalid("image is required in DockerTemplateBase"),
                mounts = templateBaseMap.validateAndGet("mounts").isList().defaultValueIfInvalid(emptyList<String>()) as List<String>,
                environmentsString = templateBaseMap.validateAndGet("environmentsString").isString().throwIfInvalid("environmentsString is required in DockerTemplateBase")
            )
        }
    }
}

data class Connector(
    val attach: Attach
)

data class Attach(
    val user: String
)

data class RetentionStrategy(
    val idleMinutes: Int
) {
    companion object {
        fun fromMap(it: Map<String, Any>): RetentionStrategy {
            return RetentionStrategy(
                idleMinutes = it.validateAndGet("idleMinutes").isNumber().throwIfInvalid("retentionStrategy.idleMinutes is required in DockerTemplate") as Int
            )
        }
    }
}