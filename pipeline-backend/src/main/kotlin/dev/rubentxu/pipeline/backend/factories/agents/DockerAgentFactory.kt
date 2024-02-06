package dev.rubentxu.pipeline.backend.factories.agents


import arrow.core.raise.result
import arrow.fx.coroutines.parMap
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.agents.DockerAgent
import dev.rubentxu.pipeline.model.agents.DockerTemplate
import dev.rubentxu.pipeline.model.agents.DockerTemplateBase

class DockerAgentFactory {

    companion object : PipelineDomainFactory<List<DockerAgent>> {
        override val rootPath: String = "agents.clouds[*].docker"


        override suspend fun create(data: PropertySet): Result<List<DockerAgent>> = result {
            getRootListPropertySet(data).bind()
                ?.parMap { properties: PropertySet ->
                    val templates: List<DockerTemplate> = DockerTemplateFactory.create(properties).bind()

                    DockerAgent(
                        id = IDComponent.create(properties.required<String>("id").bind()),
                        name = properties.required<String>("name").bind(),
                        dockerHost = properties.required<String>("dockerApi.dockerHost.uri").bind(),
                        templates = templates,
                        labels = properties.required<List<String>>("labels").bind(),
                        type = "docker"
                    )
                } ?: emptyList()
        }
    }
}

class DockerTemplateFactory {

    companion object : PipelineDomainFactory<List<DockerTemplate>> {
        override val rootPath: String = "templates"


        override suspend fun create(data: PropertySet): Result<List<DockerTemplate>> = result {
            getRootListPropertySet(data).bind()
                ?.parMap { properties: PropertySet ->
                    val labelString: String = properties.required<String>("labelString").bind()
                    val templateBaseMap: PropertySet = properties.required<PropertySet>("dockerTemplateBase").bind()
                    DockerTemplate(
                        labelString = labelString,
                        dockerTemplateBase = DockerTemplateBaseFactory.create(templateBaseMap).bind(),
                        remoteFs = properties.required<String>("remoteFs").bind(),
                        user = properties.required<String>("connector.attach.user").bind(),
                        instanceCapStr = properties.required<String>("instanceCapStr").bind(),
                        retentionStrategy = properties.required<Int>("retentionStrategy.idleMinutes").bind(),
                    )
                } ?: emptyList()
        }
    }
}

class DockerTemplateBaseFactory {

    companion object : PipelineDomainFactory<DockerTemplateBase> {
        override val rootPath: String = "dockerTemplateBase"

        override suspend fun create(data: PropertySet): Result<DockerTemplateBase> = result {
            val templateBase = getRootPropertySet(data).bind()
            DockerTemplateBase(
                image = templateBase.required<String>("image").bind(),
                mounts = templateBase.required<List<String>>("mounts").bind(),
                environmentsString = templateBase.required<String>("environmentsString").bind(),
            )
        }
    }
}