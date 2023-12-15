package dev.rubentxu.pipeline.model.pipeline

interface Agent{
    open val label: String
}

class AnyAgent(
    override val label: String,
): Agent {}


class DockerAgent(
    override val label: String = "docker",
    val image: String = "",
    val tag: String = "",
    val host: String = "",
) : Agent {}

class KubernetesAgent(
    override val label: String = "kubernetes",
    val yaml: String = "",
) : Agent {}


