package dev.rubentxu.pipeline.backend.factories.agents

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.agents.Agent
import dev.rubentxu.pipeline.model.mapper.*
import pipeline.kotlin.extensions.resolveValueExpressions

class AgentsProviderFactory {
    context(Raise<PropertiesError>)
    companion object {
        context(Raise<PropertiesError>)
        suspend fun create(rawYaml: PropertySet): IAgentProvider {
            val resolvedYaml: Map<String, Any> = rawYaml.resolveValueExpressions() as Map<String, Any>

            val agents: MutableMap<IDComponent, Agent> = createAgents(resolvedYaml.toPropertySet()).bind()
                .associateBy { it.id } as MutableMap<IDComponent, Agent>

            return AgentsProvider(
                agents = agents
            )
        }

        private suspend fun createAgents(agentsConfig: PropertySet): Either<PropertiesError, List<Agent>> =
            either {
                parZip(
                    { DockerAgentFactory.create(agentsConfig) },
                    { KubernetesAgentFactory.create(agentsConfig) }
                ) { dockerAgent, kubernetesAgent ->
                    buildList {
                        addAll(dockerAgent)
                        addAll(kubernetesAgent)
                    }
                }
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

