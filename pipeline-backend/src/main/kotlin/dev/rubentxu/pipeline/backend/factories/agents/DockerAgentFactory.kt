package dev.rubentxu.pipeline.backend.factories.agents


import arrow.core.raise.result
import arrow.fx.coroutines.parMap
import dev.rubentxu.pipeline.backend.coroutines.parZipResult
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.backend.mapper.toResult
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.agents.DockerAgent
import dev.rubentxu.pipeline.model.agents.DockerTemplate
import dev.rubentxu.pipeline.model.agents.DockerTemplateBase

class DockerAgentFactory {

    companion object : PipelineDomainFactory<List<DockerAgent>> {
        override val rootPath: String = "agents.clouds[*].docker"

        override suspend fun create(data: PropertySet): Result<List<DockerAgent>> = runCatching {
            val result = getRootListPropertySet(data)
                ?.parMap { properties: PropertySet ->
                    createDockerAgent(properties).getOrThrow()
                } ?: emptyList()

            result
        }

        suspend fun createDockerAgent(dockerProperties: PropertySet): Result<DockerAgent> {
            return parZipResult(
                { DockerTemplateFactory.create(dockerProperties) },
                { dockerProperties.required<String>("id") },
                { dockerProperties.required<String>("name") },
                { dockerProperties.required<String>("dockerApi.dockerHost.uri") },
                { dockerProperties.required<List<String>>("labels") },
                { "docker".toResult() }
            ) { templates, id, name, dockerHost, labels, type ->
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


        override suspend fun create(data: PropertySet): Result<List<DockerTemplate>> = result {
            getRootListPropertySet(data)
                ?.parMap { properties: PropertySet ->
                    createDockerTemplate(properties).getOrThrow()
                } ?: emptyList()
        }

        suspend fun createDockerTemplate(dockerTemplateProperties: PropertySet): Result<DockerTemplate> {
            return parZipResult(
                { DockerTemplateBaseFactory.create(dockerTemplateProperties.required<PropertySet>("dockerTemplateBase").getOrThrow()) },
                { dockerTemplateProperties.required<String>("labelString") },
                { dockerTemplateProperties.required<String>("remoteFs") },
                { dockerTemplateProperties.required<String>("connector.attach.user") },
                { dockerTemplateProperties.required<String>("instanceCapStr") },
                { dockerTemplateProperties.required<Int>("retentionStrategy.idleMinutes") }
            ) { dockerTemplateBase, labelString, remoteFs, user, instanceCapStr, retentionStrategy ->
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
            val templateBase = getRootPropertySet(data)
            return parZipResult(
                { templateBase.required<String>("image") },
                { templateBase.required<List<String>>("mounts") },
                { templateBase.required<String>("environmentsString") }
            ) { image, mounts, environmentsString ->
                DockerTemplateBase(
                    image = image,
                    mounts = mounts,
                    environmentsString = environmentsString,
                )
            }
        }
    }
}