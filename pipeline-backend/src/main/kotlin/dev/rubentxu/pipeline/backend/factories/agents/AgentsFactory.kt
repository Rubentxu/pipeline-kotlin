package dev.rubentxu.pipeline.backend.factories.agents

import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.Agent
import dev.rubentxu.pipeline.model.mapper.PropertySet

class AgentsFactory {
    companion object : PipelineDomainFactory<Agent> {
        override val rootPath: String = "pipeline.agents"
        override val instanceName: String = "Agent"

        override suspend fun create(data: PropertySet): Agent {
           if (data.containsKey("docker")) {
               return DockerAgentFactory.create(data)
           } else if (data.containsKey("kubernetes")) {
               return KubernetesAgentFactory.create(data)
           } else {
               throw IllegalArgumentException("Agent type not supported")
           }
        }
    }
}