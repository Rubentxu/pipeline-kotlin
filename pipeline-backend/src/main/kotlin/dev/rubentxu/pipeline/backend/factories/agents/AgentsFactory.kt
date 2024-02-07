package dev.rubentxu.pipeline.backend.factories.agents

import dev.rubentxu.pipeline.backend.coroutines.parZipResult
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.resolveValueExpressions
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.agents.Agent

class AgentsProviderFactory {

    companion object {

        suspend fun create(rawYaml: PropertySet): IAgentProvider {
            val resolvedYaml: PropertySet = rawYaml.resolveValueExpressions()
            val agents: Map<IDComponent, Agent> = createAgents(resolvedYaml)
                .getOrThrow()
                .associateBy { it.id }


            return AgentsProvider(
                agents = agents.toMutableMap()
            )
        }

        private suspend fun createAgents(agentsConfig: PropertySet): Result<List<Agent>> =
            parZipResult(
                { KubernetesAgentFactory.create(agentsConfig) },
                { DockerAgentFactory.create(agentsConfig) }
            ) { kubernetesAgents, dockerAgents ->
                kubernetesAgents + dockerAgents
            }
    }
}

interface IAgentProvider {
    fun getAgent(id: IDComponent): Result<Agent>

    fun registerAgent(agent: Agent): Result<Unit>

    fun unregisterAgent(id: IDComponent): Result<Unit>

    fun getAgents(): Result<List<Agent>>

    fun getAgentsByType(type: String): Result<List<Agent>>

    fun getAgentsByLabel(label: String): Result<List<Agent>>
}

class AgentsProvider(
    private val agents: MutableMap<IDComponent, Agent>,
) : IAgentProvider {
    override fun getAgent(id: IDComponent): Result<Agent> {
        return agents[id]?.let { Result.success(it) } ?: Result.failure(Exception("Agent with id $id not found"))
    }

    override fun registerAgent(agent: Agent): Result<Unit> {
        return agents.put(agent.id, agent)?.let { Result.success(Unit) }
            ?: Result.failure(Exception("Agent with id ${agent.id} not found"))
    }

    override fun unregisterAgent(id: IDComponent): Result<Unit> {
        return agents.remove(id)?.let { Result.success(Unit) }
            ?: Result.failure(Exception("Agent with id $id not found"))
    }

    override fun getAgents(): Result<List<Agent>> {
        return Result.success(agents.values.toList())
    }

    override fun getAgentsByType(type: String): Result<List<Agent>> {
        return Result.success(agents.values.filter { it.type == type })
    }

    override fun getAgentsByLabel(label: String): Result<List<Agent>> {
        return Result.success(agents.values.filter { it.labels.contains(label) })
    }
}

