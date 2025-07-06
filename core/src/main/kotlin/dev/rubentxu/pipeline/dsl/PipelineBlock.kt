package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.steps.EnvVars

/**
 * Main pipeline configuration block following modern Kotlin DSL best practices.
 * This class provides a type-safe DSL for defining CI/CD pipelines.
 */
@PipelineDsl
class PipelineBlock {
    private var stages: List<StageExecutor> = emptyList()
    private var agent: Agent = AnyAgent("any")
    private var env: EnvVars = EnvVars(mutableMapOf())
    private var postExecution: PostExecution = PostExecution()

    /**
     * Configures the agent for the pipeline execution.
     * Uses a type-safe DSL builder pattern.
     *
     * @param block Configuration block for agent settings
     */
    fun agent(block: AgentBlock.() -> Unit) {
        agent = AgentBlock().apply(block).agent
    }

    /**
     * Configures environment variables for the pipeline.
     * Uses a type-safe DSL builder pattern.
     *
     * @param block Configuration block for environment variables
     */
    fun environment(block: EnvironmentBlock.() -> Unit) {
        env = EnvVars(EnvironmentBlock().apply(block).map)
    }

    /**
     * Defines the stages to be executed in the pipeline.
     * Uses a type-safe DSL builder pattern.
     *
     * @param block Configuration block for stages
     */
    fun stages(block: StagesCollectionBlock.() -> Unit) {
        stages = StagesCollectionBlock().apply(block).build()
    }

    /**
     * Defines post-execution actions for the pipeline.
     * Uses a type-safe DSL builder pattern.
     *
     * @param block Configuration block for post-execution actions
     */
    fun post(block: PostExecutionBlock.() -> Unit) {
        postExecution = PostExecutionBlock().apply(block).build()
    }

    /**
     * Builds the pipeline configuration into a Pipeline instance.
     * This method is internal and should only be called by the pipeline engine.
     *
     * @param configuration The pipeline configuration
     * @return A fully configured Pipeline instance
     */
    internal fun build(configuration: IPipelineConfig): Pipeline {
        require(stages.isNotEmpty()) { "Pipeline must have at least one stage" }
        
        return Pipeline(
            stages = stages,
            agent = agent,
            env = env,
            postExecution = postExecution,
            pipelineConfig = configuration
        )
    }
}