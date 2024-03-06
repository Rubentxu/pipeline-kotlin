package dev.rubentxu.pipeline.backend.factories.agents


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

        override suspend fun create(data: PropertySet): Result<List<DockerAgent>> = runCatching {
            val result = getRootListPropertySet(data)
                ?.map { properties: PropertySet ->
                    createDockerAgent(properties).getOrThrow()
                } ?: emptyList()

            result
        }

        suspend fun createDockerAgent(dockerProperties: PropertySet): Result<DockerAgent> {
            return runCatching {
                val templates = DockerTemplateFactory.create(dockerProperties).getOrThrow()
                val id = dockerProperties.required<String>("id").getOrThrow()
                val name = dockerProperties.required<String>("name").getOrThrow()
                val dockerHost = dockerProperties.required<String>("dockerApi.dockerHost.uri").getOrThrow()
                val labels = dockerProperties.required<List<String>>("labels").getOrThrow()
                val type = "docker"

                DockerAgent(
                    id = IDComponent.create(id),
                    name = name,
                    dockerHost = dockerHost,
                    labels = labels,
                    type = type,
                    templates = templates,
                )
            }
        }
    }
}

class DockerTemplateFactory {

    companion object : PipelineDomainFactory<List<DockerTemplate>> {
        override val rootPath: String = "templates"


        override suspend fun create(data: PropertySet): Result<List<DockerTemplate>> = runCatching {
            val result = getRootListPropertySet(data)
                ?.map { properties: PropertySet ->
                    createDockerTemplate(properties).getOrThrow()
                } ?: emptyList()

            result
        }

        suspend fun createDockerTemplate(dockerTemplateProperties: PropertySet): Result<DockerTemplate> {
            return runCatching {
                val dockerTemplateBase = DockerTemplateBaseFactory.create(
                    dockerTemplateProperties.required<PropertySet>("dockerTemplateBase").getOrThrow()
                ).getOrThrow()

                val labelString = dockerTemplateProperties.required<String>("labelString").getOrThrow()
                val remoteFs = dockerTemplateProperties.required<String>("remoteFs").getOrThrow()
                val user = dockerTemplateProperties.required<String>("connector.attach.user").getOrThrow()
                val instanceCapStr = dockerTemplateProperties.required<String>("instanceCapStr").getOrThrow()
                val retentionStrategy = dockerTemplateProperties.required<Int>("retentionStrategy.idleMinutes").getOrThrow()

                DockerTemplate(
                    labelString = labelString,
                    dockerTemplateBase = dockerTemplateBase,
                    remoteFs = remoteFs,
                    user = user,
                    instanceCapStr = instanceCapStr,
                    retentionStrategy = retentionStrategy,
                )
            }
        }
    }
}

class DockerTemplateBaseFactory {

    companion object : PipelineDomainFactory<DockerTemplateBase> {
        override val rootPath: String = "dockerTemplateBase"

        override suspend fun create(data: PropertySet): Result<DockerTemplateBase> {
            return runCatching {
                val templateBase = getRootPropertySet(data)
                val image = templateBase.required<String>("image").getOrThrow()
                val mounts = templateBase.required<List<String>>("mounts").getOrThrow()
                val environmentsString = templateBase.required<String>("environmentsString").getOrThrow()

                DockerTemplateBase(
                    image = image,
                    mounts = mounts,
                    environmentsString = environmentsString,
                )
            }
        }
    }
}