package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.either
import arrow.core.raise.zipOrAccumulate
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.DockerAgent
import dev.rubentxu.pipeline.model.agents.DockerTemplate
import dev.rubentxu.pipeline.model.agents.DockerTemplateBase
import dev.rubentxu.pipeline.model.agents.RetentionStrategy
import dev.rubentxu.pipeline.model.mapper.*
import dev.rubentxu.pipeline.model.validations.validateAndGet

class DockerAgentFactory {
    companion object : PipelineDomainFactory<DockerAgent> {
        override val rootPath: String = "pipeline.agents"
        override val instanceName: String = "DockerAgent"

        suspend fun create(data: PropertySet): Either<NonEmptyList<ValidationError>, DockerAgent> = either {
           zipOrAccumulate(
                { data.required<String>("id".propertyPath()).map { IDComponent.create(it)} },
                { data.required<String>("docker.name".propertyPath()) },
                { data.required<String>("docker.dockerApi.dockerHost.uri".propertyPath()) },
                { data.required<List<PropertySet>>("docker.templates".propertyPath()) },
                { data.required<List<String>>("docker.labels".propertyPath()) }
            ) { id, name, dockerHost, templatesMap, labels ->

                val templates: List<DockerTemplate> = templatesMap.unwrap().map { properties: PropertySet ->
                    DockerTemplateFactory.create(properties)
                }.toList()

                DockerAgent(
                    id = id.unwrap(),
                    name = name.unwrap(),
                    dockerHost = dockerHost.unwrap(),
                    templates = templates,
                    labels = labels.unwrap()
                )
            }
        }
    }
}

class DockerTemplateFactory {
    companion object : PipelineDomainFactory<DockerTemplate> {
        override val rootPath: String = "docker.templates"
        override val instanceName: String = "DockerTemplate"

        override suspend fun create(data: PropertySet): Either<NonEmptyList<ValidationError>,DockerTemplate> = {
            val templateBaseMap: Map<String, Any> = data.required<Map<String, Any>>("dockerTemplateBase".propertyPath()).unwrap()

            return DockerTemplate(
                labelString = data.required<String>("labelString".propertyPath()).unwrap(),
                dockerTemplateBase = DockerTemplateBaseFactory.create(templateBaseMap),
                remoteFs = data.required<String>("remoteFs".propertyPath()).unwrap(),
                user = data.required<String>("connector.attach.user".propertyPath()).unwrap(),
                instanceCapStr = data.required<String>("instanceCapStr".propertyPath()).unwrap(),
                retentionStrategy = RetentionStrategyFactory.create(data)
            )
        }
    }
}

class DockerTemplateBaseFactory {
    companion object : PipelineDomainFactory<DockerTemplateBase> {
        override val rootPath: String = "dockerTemplateBase"
        override val instanceName: String = "DockerTemplateBase"

        override suspend fun create(data: PropertySet): DockerTemplateBase {
            return DockerTemplateBase(
                image = data.required<String>("image".propertyPath()).unwrap(),
                mounts = data.required<List<String>>("mounts".propertyPath()).unwrap(),
                environmentsString = data.required<String>("environmentsString".propertyPath()).unwrap()
            )
        }
    }
}

class RetentionStrategyFactory {
    companion object : PipelineDomainFactory<RetentionStrategy> {
        override val rootPath: String = "retentionStrategy"
        override val instanceName: String = "RetentionStrategy"

        override suspend fun create(data: PropertySet): RetentionStrategy {
            return RetentionStrategy(
                idleMinutes = data.required<Int>("retentionStrategy.idleMinutes".propertyPath()).unwrap()
            )
        }
    }
}