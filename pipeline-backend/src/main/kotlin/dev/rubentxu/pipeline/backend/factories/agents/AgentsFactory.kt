package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.raise.Raise
import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.Agent
import dev.rubentxu.pipeline.model.mapper.*

class AgentsFactory {
    context(Raise<ValidationError>)
    companion object : PipelineDomainFactory<Agent> {
        override val rootPath: PathSegment = "pipeline.agents".pathSegment()


        context(Raise<ValidationError>)
        override suspend fun create(data: PropertySet): Agent {
            val docker = data.optional<PropertySet>("docker".pathSegment())
            val kubernetes = data.optional<PropertySet>("kubernetes".pathSegment())

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