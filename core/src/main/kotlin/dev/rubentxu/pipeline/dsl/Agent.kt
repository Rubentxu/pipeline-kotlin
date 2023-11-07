package dev.rubentxu.pipeline.dsl

@PipelineDsl
sealed class Agent
@PipelineDsl
data class AnyAgent(
    var label: String = ""
): Agent() {
    init {
       label = "any"
    }
}
@PipelineDsl
data class DockerAgent(
    var label: String = "docker"
): Agent()
@PipelineDsl
data class KubernetesAgent(
    var label: String = "kubernetes"
): Agent()


@PipelineDsl
class AgentBlock() {

    fun any(block: AnyAgent.() -> Any): Agent {
        val agent = AnyAgent()
        agent.block()
        return agent
    }

    fun docker(block: DockerAgent.() -> DockerAgent): DockerAgent {
        val agent = DockerAgent()
        return agent.block()
    }

    fun kubernetes(block: KubernetesAgent.() -> KubernetesAgent): KubernetesAgent {
        val agent = KubernetesAgent()
        return agent.block()
    }

}