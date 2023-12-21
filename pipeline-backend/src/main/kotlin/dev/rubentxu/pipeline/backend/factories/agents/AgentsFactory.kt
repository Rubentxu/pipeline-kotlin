package dev.rubentxu.pipeline.backend.factories.agents

import dev.rubentxu.pipeline.model.PipelineDomainFactory
import dev.rubentxu.pipeline.model.agents.Agent

class AgentsFactory {
    companion object : PipelineDomainFactory<Agent> {

        override suspend fun create(data: Map<String, Any>): Agent {
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