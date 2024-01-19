package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.NonEmptyList
import arrow.core.raise.Raise
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.Agent
import dev.rubentxu.pipeline.model.mapper.*

class AgentsFactory {
    context(Raise<PropertiesError>)
    companion object : PipelineDomainFactory<Agent> {
        override val rootPath: PropertyPath = "agents.clouds".propertyPath()


        context(Raise<PropertiesError>, Raise<NonEmptyList<PropertiesError>>)
        override suspend fun create(data: PropertySet): Agent {
            val clouds = getRootListPropertySet(data)


            val docker = clouds.firstOrNull<PropertySet>("docker")
            val kubernetes = clouds.firstOrNull<PropertySet>("kubernetes")

            if (docker != null) {
                return DockerAgentFactory.create(docker)
            } else if (kubernetes != null) {
                return KubernetesAgentFactory.create(kubernetes)
            } else {
                throw IllegalArgumentException("Agent type not supported")
            }
        }
    }
}

