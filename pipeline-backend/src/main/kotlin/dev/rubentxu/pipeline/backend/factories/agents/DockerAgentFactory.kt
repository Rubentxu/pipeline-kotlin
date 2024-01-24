package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.NonEmptyList
import arrow.core.raise.Raise
import arrow.fx.coroutines.parMap
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.DockerAgent
import dev.rubentxu.pipeline.model.agents.DockerTemplate
import dev.rubentxu.pipeline.model.agents.DockerTemplateBase
import dev.rubentxu.pipeline.model.mapper.*

class DockerAgentFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<DockerAgent>> {
        override val rootPath: PropertyPath = "agents.clouds[*].docker".propertyPath()

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<DockerAgent> {

            return getRootListPropertySet(data)
                .parMap { properties: PropertySet ->
                    val templates: List<DockerTemplate> = DockerTemplateFactory.create(properties)

                    DockerAgent(
                        id = IDComponent.create(properties.required<String>("id")),
                        name = properties.required<String>("name"),
                        dockerHost = properties.required<String>("dockerApi.dockerHost.uri"),
                        templates = templates,
                        labels = properties.required<List<String>>("labels"),
                        type = "docker"
                    )
                }


        }
    }
}

class DockerTemplateFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<List<DockerTemplate>> {
        override val rootPath: PropertyPath = "templates".propertyPath()


        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): List<DockerTemplate> {
            return getRootListPropertySet(data)
                .parMap { properties: PropertySet ->
                    val labelString: String = properties.required<String>("labelString")
                    val templateBaseMap: PropertySet = properties.required<PropertySet>("dockerTemplateBase")
                    DockerTemplate(
                        labelString = labelString,
                        dockerTemplateBase = DockerTemplateBaseFactory.create(templateBaseMap),
                        remoteFs = properties.required<String>("remoteFs"),
                        user = properties.required<String>("connector.attach.user"),
                        instanceCapStr = properties.required<String>("instanceCapStr"),
                        retentionStrategy = properties.required<Int>("retentionStrategy.idleMinutes")
                    )
                }
        }
    }
}

class DockerTemplateBaseFactory {

    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<DockerTemplateBase> {
        override val rootPath: PropertyPath = "dockerTemplateBase".propertyPath()

        context(Raise<PropertiesError>)
        override suspend fun create(data: PropertySet): DockerTemplateBase {
            val templateBase = getRootPropertySet(data)
            return DockerTemplateBase(
                image = templateBase.required<String>("image"),
                mounts = templateBase.required<List<String>>("mounts"),
                environmentsString = templateBase.required<String>("environmentsString")
            )
        }
    }
}