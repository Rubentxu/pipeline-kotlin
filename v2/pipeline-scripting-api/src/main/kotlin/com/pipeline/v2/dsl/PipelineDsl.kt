package com.pipeline.v2.dsl

/**
 * Specification of a pipeline as built by the DSL.
 */
data class PipelineSpec(
    val stages: List<StageSpec>,
)

/**
 * Specification of a single stage within a pipeline.
 */
data class StageSpec(
    val name: String,
    val steps: List<StepSpec>,
)

/**
 * Sealed hierarchy of steps that a stage can contain.
 */
sealed interface StepSpec {
    val name: String
    val type: String

    data class Echo(val text: String) : StepSpec {
        override val name: String get() = "echo"
        override val type: String get() = "echo"
    }

    data class Shell(val command: String) : StepSpec {
        override val name: String get() = "sh"
        override val type: String get() = "sh"
    }
}

/**
 * Top-level `pipeline { }` DSL entry point.
 *
 * Example:
 * ```
 * pipeline {
 *     stages {
 *         stage("Build") {
 *             echo("hello")
 *             sh("echo from sh")
 *         }
 *     }
 * }
 * ```
 */
fun pipeline(block: PipelineScope.() -> Unit): PipelineSpec {
    val scope = PipelineScope()
    scope.block()
    return PipelineSpec(stages = scope.buildStages())
}

/**
 * Receiver scope for the `stages { }` block inside `pipeline { }`.
 */
class PipelineScope {
    private val stageBuilders = mutableListOf<StageBuilder>()

    fun stages(block: StagesScope.() -> Unit) {
        val scope = StagesScope()
        scope.block()
        scope.buildStageBuilders().forEach { stageBuilders.add(it) }
    }

    fun buildStages(): List<StageSpec> = stageBuilders.map { it.build() }
}

/**
 * Receiver scope for the `stage("name") { }` block inside `stages { }`.
 */
class StagesScope {
    private val stageBuilders = mutableListOf<StageBuilder>()

    fun stage(name: String, block: StageScope.() -> Unit) {
        val scope = StageScope(name)
        scope.block()
        stageBuilders.add(scope.toStageBuilder())
    }

    fun buildStageBuilders(): List<StageBuilder> = stageBuilders
}

/**
 * Receiver scope for the step block inside `stage("name") { }`.
 */
class StageScope(private val stageName: String) {
    private val steps = mutableListOf<StepSpec>()

    fun echo(text: String) {
        steps.add(StepSpec.Echo(text))
    }

    fun sh(command: String) {
        steps.add(StepSpec.Shell(command))
    }

    fun toStageBuilder(): StageBuilder = StageBuilder(stageName, steps.toList())
}

/**
 * Builder for a stage, capturing its name and steps.
 */
class StageBuilder(
    private val name: String,
    private val steps: List<StepSpec>,
) {
    fun build(): StageSpec = StageSpec(name, steps)
}
