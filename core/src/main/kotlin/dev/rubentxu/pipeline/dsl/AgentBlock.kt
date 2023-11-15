package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.pipeline.*

@PipelineDsl
class AgentBlock() {
    lateinit var agent: Agent

    fun any(block: AnyAgentBlock.() -> Unit) {
        this.agent = AnyAgentBlock().apply(block).build()

    }

    fun docker(block: DockerAgentBlock.() -> Unit) {
        this.agent = DockerAgentBlock().apply(block).build()

    }

    fun kubernetes(block: KubernetesAgentBlock.() -> Unit) {
        this.agent = KubernetesAgentBlock().apply(block).build()
    }

}

@PipelineDsl
class AnyAgentBlock {
    var label: String = "any"
    fun build(): Agent {
        return AnyAgent(label = label)
    }
}




@PipelineDsl
class DockerAgentBlock {
    var label: String = "docker"
    var image: String = ""
    var tag: String = ""
    var host: String = ""

    fun build(): Agent {
        return DockerAgent(label = label, image = image, tag = tag, host = host)
    }
}
@PipelineDsl
class KubernetesAgentBlock {
    var label: String = "kubernetes"
    var yaml: String = ""

    fun build(): Agent {
        return KubernetesAgent(label = label, yaml = yaml)
    }
}
