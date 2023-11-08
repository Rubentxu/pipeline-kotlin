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
    var label: String = "docker",
    var image: String = "",
    var tag: String = "",
): Agent()
@PipelineDsl
data class KubernetesAgent(
    var label: String = "kubernetes",
    var yaml: String = "",
): Agent()


