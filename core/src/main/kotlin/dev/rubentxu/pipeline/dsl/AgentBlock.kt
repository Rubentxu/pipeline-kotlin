package dev.rubentxu.pipeline.dsl

@PipelineDsl
class AgentBlock(pipeline: Pipeline) {
    var logger = pipeline.logger
    lateinit var agent: Agent

    fun any(block: AnyAgent.() -> Unit) {
        val agent = AnyAgent()
        agent.block()
        this.agent = agent
    }

    fun docker(block: DockerAgent.() -> Unit) {
        val agent = DockerAgent()
        agent.block()
        this.agent = agent
    }

    fun kubernetes(block: KubernetesAgent.() -> KubernetesAgent) {
        val agent = KubernetesAgent()
        agent.block()
        this.agent = agent
    }

}