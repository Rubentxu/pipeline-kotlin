package dev.rubentxu.pipeline.core.dsl


import dev.rubentxu.pipeline.core.events.EventStore
import dev.rubentxu.pipeline.core.interfaces.ILogger
import dev.rubentxu.pipeline.core.interfaces.IPipelineContext
import dev.rubentxu.pipeline.core.pipeline.EnvVars
import dev.rubentxu.pipeline.core.pipeline.Pipeline
import dev.rubentxu.pipeline.core.pipeline.PostExecution
import dev.rubentxu.pipeline.model.pipeline.*


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


    fun build(context: IPipelineContext): Pipeline {
        val eventStore: EventStore = context.getComponent(EventStore::class)
        val logger: ILogger = context.getComponent(ILogger::class)
        return Pipeline(
            stages = stages,
            postExecution = postExecution,
            logger = logger,
            eventStore = eventStore,

        )
    }
}