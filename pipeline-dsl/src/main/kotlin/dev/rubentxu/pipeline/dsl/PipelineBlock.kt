package dev.rubentxu.pipeline.dsl


import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.model.steps.EnvVars

class PipelineBlock() {
    var stages: List<StageExecutor> = mutableListOf()
    var agent: Agent = AnyAgent("any")
    var env: EnvVars = EnvVars(mutableMapOf())
    var postExecution: PostExecution = PostExecution()

    /**
     * // * This function sets the agent for the pipeline to any available
     * agent. // * // * @param agentParam Placeholder for the any available
     * agent. //
     */
    fun agent(block: AgentBlock.() -> Unit) {
//        logger.system("Running pipeline using any available agent... ")
        val agentBlock = AgentBlock()
        agentBlock.block()
        agent = agentBlock.agent
    }

    /**
     * This function sets the environment variables for the pipeline.
     *
     * @param block A block of code to define the environment variables.
     */
    fun environment(block: EnvironmentBlock.() -> Unit) {
        val environmentBlock = EnvironmentBlock()
        environmentBlock.block()
        env = EnvVars(environmentBlock.map)

    }

    /**
     * This function adds a stage to the pipeline.
     *
     * @param block A block of code to run in the stage.
     * @param name The name of the stage.
     */
    fun stages(block: StagesCollectionBlock.() -> Unit) {
        stages = StagesCollectionBlock().apply(block).build()
    }

    /**
     * Defines a block of code to execute after all stages have been executed.
     *
     * @param block The block of code to execute.
     */
    fun post(block: PostExecutionBlock.() -> Unit) {
        postExecution = PostExecutionBlock().apply(block).build()
    }

    /**
     * This function builds the pipeline.
     *
     * @return A Pipeline instance representing the pipeline.
     */
    fun createPipeline(): Pipeline {
        return Pipeline(
            stages = stages,
            env = env,
            postExecution = postExecution
        )
    }

    fun createAgent(): Agent {
        return agent
    }

    fun build(): Pipeline {

        return Pipeline(
            stages = stages,
            env = env,
            postExecution = postExecution
        )
    }
}