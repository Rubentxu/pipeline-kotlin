package dev.rubentxu.pipeline.backend.factories.agents

import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.DockerAgent
import dev.rubentxu.pipeline.model.agents.DockerTemplate
import dev.rubentxu.pipeline.model.agents.DockerTemplateBase
import dev.rubentxu.pipeline.model.agents.RetentionStrategy
import dev.rubentxu.pipeline.model.validations.validateAndGet

class DockerAgentFactory {
    companion object : PipelineDomainFactory<DockerAgent> {

        override fun create(data: Map<String, Any>): DockerAgent {
            val id: IDComponent = IDComponent.create(data.validateAndGet("id")
                .isString()
                .throwIfInvalid("id is required in DockerCloudConfig"))

            val templatesMap = data.validateAndGet("docker.templates")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val templates: List<DockerTemplate> = templatesMap.map {
                return@map DockerTemplateFactory.create(it)
            }

            val labels: List<String> = data.validateAndGet("docker.labels")
                .isList()
                .defaultValueIfInvalid(emptyList<String>()) as List<String>

            return DockerAgent(
                id = id,
                name = data.validateAndGet("docker.name").isString()
                    .throwIfInvalid("name is required in DockerCloudConfig"),
                dockerHost = data.validateAndGet("docker.dockerApi.dockerHost.uri").isString()
                    .throwIfInvalid("dockerHost is required in DockerCloudConfig"),
                templates = templates,
                labels = labels
            )
        }
    }
}

class DockerTemplateFactory {
    companion object : PipelineDomainFactory<DockerTemplate> {
        override fun create(data: Map<String, Any>): DockerTemplate {
            val templateBaseMap: Map<String, Any> = data.validateAndGet("dockerTemplateBase")
                .isMap()
                .throwIfInvalid("dockerTemplateBase is required in DockerTemplate") as Map<String, Any>

            return DockerTemplate(
                labelString = data.validateAndGet("labelString").isString()
                    .throwIfInvalid("labelString is required in DockerTemplate"),
                dockerTemplateBase = DockerTemplateBaseFactory.create(templateBaseMap),
                remoteFs = data.validateAndGet("remoteFs").isString()
                    .throwIfInvalid("remoteFs is required in DockerTemplate"),
                user = data.validateAndGet("connector.attach.user").isString()
                    .throwIfInvalid("connector.attach.user is required in DockerTemplate"),
                instanceCapStr = data.validateAndGet("instanceCapStr").isString()
                    .throwIfInvalid("instanceCapStr is required in DockerTemplate"),
                retentionStrategy = RetentionStrategyFactory.create(data)
            )
        }
    }
}

class DockerTemplateBaseFactory {
    companion object : PipelineDomainFactory<DockerTemplateBase> {
        override fun create(data: Map<String, Any>): DockerTemplateBase {
            return DockerTemplateBase(
                image = data.validateAndGet("image").isString()
                    .throwIfInvalid("image is required in DockerTemplateBase"),
                mounts = data.validateAndGet("mounts").isList()
                    .defaultValueIfInvalid(emptyList<String>()) as List<String>,
                environmentsString = data.validateAndGet("environmentsString").isString()
                    .throwIfInvalid("environmentsString is required in DockerTemplateBase")
            )
        }
    }
}

class RetentionStrategyFactory {
    companion object : PipelineDomainFactory<RetentionStrategy> {
        override fun create(data: Map<String, Any>): RetentionStrategy {
            return RetentionStrategy(
                idleMinutes = data.validateAndGet("retentionStrategy.idleMinutes").isNumber().throwIfInvalid() as Int
            )
        }
    }
}