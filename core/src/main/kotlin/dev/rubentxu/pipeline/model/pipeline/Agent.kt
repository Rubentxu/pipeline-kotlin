package dev.rubentxu.pipeline.model.pipeline

open class Agent(open val label: String)

data class DockerAgent(
    override val label: String = "docker",
    val image: String = "",
    val tag: String = "",
    val host: String = "",
): Agent(label)

data class KubernetesAgent(
    override val label: String = "kubernetes",
    val yaml: String = "",
): Agent(label)

data class PipelineAgent(
    val agent: Agent,
)
