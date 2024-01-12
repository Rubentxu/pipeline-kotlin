package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.NonEmptyList
import arrow.core.raise.Raise
import arrow.core.raise.zipOrAccumulate
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.DockerAgent
import dev.rubentxu.pipeline.model.agents.DockerTemplate
import dev.rubentxu.pipeline.model.agents.DockerTemplateBase
import dev.rubentxu.pipeline.model.agents.RetentionStrategy
import dev.rubentxu.pipeline.model.mapper.*

class DockerAgentFactory {

    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<DockerAgent> {
        override val rootPath: PropertyPath = "docker".propertyPath()

        context(Raise<NonEmptyList<ValidationError>>)
        override suspend fun create(data: PropertySet): DockerAgent {
           zipOrAccumulate(
                { data.required<String>("id".propertyPath()) },
                { data.required<String>("docker.name".propertyPath()) },
                { data.required<String>("docker.dockerApi.dockerHost.uri".propertyPath()) },
                { data.required<List<PropertySet>>("docker.templates".propertyPath()) },
                { data.required<List<String>>("docker.labels".propertyPath()) }
            ) { id, name, dockerHost, templatesMap, labels ->

                val templates: List<DockerTemplate> = templatesMap.map { properties: PropertySet ->
                    DockerTemplateFactory.create(properties)
                }.toList()

                return DockerAgent(
                    id = IDComponent.create(id) ,
                    name = name,
                    dockerHost = dockerHost,
                    templates = templates,
                    labels = labels
                )
            }
        }
    }
}

class DockerTemplateFactory {

    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<DockerTemplate> {
        override val rootPath: PropertyPath = "templates".propertyPath()


        context(Raise<NonEmptyList<ValidationError>>)
        override suspend fun create(data: PropertySet): DockerTemplate {
            val template = getRootPropertySet(data)
            val templateBaseMap: Map<String, Any> = data.required<Map<String, Any>>("dockerTemplateBase".propertyPath())

            return DockerTemplate(
                labelString = template.required<String>("labelString".propertyPath()),
                dockerTemplateBase = DockerTemplateBaseFactory.create(templateBaseMap),
                remoteFs = template.required<String>("remoteFs".propertyPath()),
                user = template.required<String>("connector.attach.user".propertyPath()),
                instanceCapStr = template.required<String>("instanceCapStr".propertyPath()),
                retentionStrategy = RetentionStrategyFactory.create(template)
            )
        }
    }
}

class DockerTemplateBaseFactory {

    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<DockerTemplateBase> {
        override val rootPath: PropertyPath = "dockerTemplateBase".propertyPath()

        context(Raise<NonEmptyList<ValidationError>>)
        override suspend fun create(data: PropertySet): DockerTemplateBase {
            val templateBase = getRootPropertySet(data)
            return DockerTemplateBase(
                image = templateBase.required<String>("image".propertyPath()),
                mounts = templateBase.required<List<String>>("mounts".propertyPath()),
                environmentsString = templateBase.required<String>("environmentsString".propertyPath())
            )
        }
    }
}

class RetentionStrategyFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<RetentionStrategy> {
        override val rootPath: PropertyPath = "retentionStrategy".propertyPath()

        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): RetentionStrategy {
            return RetentionStrategy(
                idleMinutes = data.required<Int>("retentionStrategy.idleMinutes".propertyPath())
            )
        }
    }
}