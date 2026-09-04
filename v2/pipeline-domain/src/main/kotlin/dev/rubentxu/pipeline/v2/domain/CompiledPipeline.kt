package dev.rubentxu.pipeline.v2.domain

import kotlinx.serialization.Serializable

/** Immutable, inspectable pipeline definition produced before execution. */
@Serializable
data class CompiledPipeline(
    val id: DefinitionId,
    val source: SourceDescriptor,
    val agent: AgentSpec? = null,
    val environment: EnvironmentSpec = EnvironmentSpec.empty(),
    val options: List<OptionSpec> = emptyList(),
    val parameters: List<ParameterSpec> = emptyList(),
    val tools: List<ToolSpec> = emptyList(),
    val stages: List<StageNode>,
    val post: PostSpec? = null,
    val pluginLockDigest: Digest,
) {
    init {
        require(stages.map(StageNode::id).toSet().size == stages.size) {
            "CompiledPipeline stages must have unique ids"
        }
    }
}

@Serializable
data class SourceDescriptor(val path: String, val digest: Digest) {
    init { require(path.isNotBlank()) { "SourceDescriptor.path must not be blank" } }
}

@JvmInline
@Serializable
value class Digest(val value: String) {
    init { require(value.isNotBlank()) { "Digest value must not be blank" } }
}

@Serializable
data class AgentSpec(val label: String, val remoteUri: String? = null) {
    init { require(label.isNotBlank()) { "AgentSpec.label must not be blank" } }
}

@Serializable
data class EnvironmentSpec(val values: Map<String, String>) {
    init { require(values.keys.none(String::isBlank)) { "Environment keys must not be blank" } }
    companion object { fun empty() = EnvironmentSpec(emptyMap()) }
}

@Serializable
data class OptionSpec(val name: String, val value: String? = null) {
    init { require(name.isNotBlank()) { "OptionSpec.name must not be blank" } }
}

@Serializable
data class ParameterSpec(val name: String, val type: String, val defaultValue: String? = null) {
    init {
        require(name.isNotBlank()) { "ParameterSpec.name must not be blank" }
        require(type.isNotBlank()) { "ParameterSpec.type must not be blank" }
    }
}

@Serializable
data class ToolSpec(val name: String, val version: String) {
    init {
        require(name.isNotBlank()) { "ToolSpec.name must not be blank" }
        require(version.isNotBlank()) { "ToolSpec.version must not be blank" }
    }
}

@Serializable
data class PostSpec(val conditions: Map<String, List<StepNode>>) {
    init { require(conditions.keys.none(String::isBlank)) { "Post condition names must not be blank" } }
}

@Serializable
data class ConditionSpec(val expression: String) {
    init { require(expression.isNotBlank()) { "ConditionSpec.expression must not be blank" } }
}

@Serializable
data class InputSpec(val message: String) {
    init { require(message.isNotBlank()) { "InputSpec.message must not be blank" } }
}

@Serializable
data class MatrixSpec(val axes: Map<String, List<String>>) {
    init {
        require(axes.isNotEmpty()) { "MatrixSpec.axes must not be empty" }
        require(axes.keys.none(String::isBlank) && axes.values.none(List<String>::isEmpty)) {
            "Matrix axes must have names and values"
        }
    }
}

@Serializable
data class StageNode(
    val id: StageId,
    val name: String,
    val agent: AgentSpec? = null,
    val environment: EnvironmentSpec = EnvironmentSpec.empty(),
    val options: List<OptionSpec> = emptyList(),
    val whenCondition: ConditionSpec? = null,
    val input: InputSpec? = null,
    val body: StageBody,
    val post: PostSpec? = null,
) {
    init { require(name.isNotBlank()) { "StageNode.name must not be blank" } }
}

@Serializable
sealed interface StageBody {
    @Serializable
    data class Steps(val steps: List<StepNode>) : StageBody
    @Serializable
    data class NestedStages(val stages: List<StageNode>) : StageBody
    @Serializable
    data class Parallel(val branches: List<StageNode>) : StageBody
    @Serializable
    data class Matrix(val matrix: MatrixSpec) : StageBody
}

@Serializable
sealed interface StepNode {
    val id: StepId
    val pluginStepId: PluginStepId
    val payload: VersionedStepPayload
}

@Serializable
data class OpaqueStepNode(
    override val id: StepId,
    override val pluginStepId: PluginStepId,
    override val payload: VersionedStepPayload,
) : StepNode

@Serializable
data class VersionedStepPayload(val schemaVersion: String, val encoded: String) {
    init {
        require(schemaVersion.isNotBlank()) { "VersionedStepPayload.schemaVersion must not be blank" }
    }
}
