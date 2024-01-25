package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.fx.coroutines.parMap
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PropertiesError
import dev.rubentxu.pipeline.model.Res
import dev.rubentxu.pipeline.model.agents.DockerAgent
import dev.rubentxu.pipeline.model.agents.DockerTemplate
import dev.rubentxu.pipeline.model.agents.DockerTemplateBase

class DockerAgentFactory {

    companion object : PipelineDomainFactory<Res<List<DockerAgent>>> {
        override val rootPath: String = "agents.clouds[*].docker"


        override suspend fun create(data: PropertySet): Res<List<DockerAgent>> = either {
            getRootListPropertySet(data)
                .parMap { properties: PropertySet ->
                    val templates: List<DockerTemplate> = DockerTemplateFactory.create(properties).bind()

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

    companion object : PipelineDomainFactory<Res<List<DockerTemplate>>> {
        override val rootPath: String = "templates"


        override suspend fun create(data: PropertySet): Res<List<DockerTemplate>> = either {
            getRootListPropertySet(data)
                .parMap { properties: PropertySet ->
                    val labelString: String = properties.required<String>("labelString")
                    val templateBaseMap: PropertySet = properties.required<PropertySet>("dockerTemplateBase")
                    DockerTemplate(
                        labelString = labelString,
                        dockerTemplateBase = DockerTemplateBaseFactory.create(templateBaseMap).bind(),
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

    companion object : PipelineDomainFactory<Res<DockerTemplateBase>> {
        override val rootPath: String = "dockerTemplateBase"

        override suspend fun create(data: PropertySet): Res<DockerTemplateBase> = either {
            val templateBase = getRootPropertySet(data)
            DockerTemplateBase(
                image = templateBase.required<String>("image"),
                mounts = templateBase.required<List<String>>("mounts"),
                environmentsString = templateBase.required<String>("environmentsString")
            )
        }
    }
}