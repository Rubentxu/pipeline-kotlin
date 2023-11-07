package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.events.EndEvent
import dev.rubentxu.pipeline.events.Event
import dev.rubentxu.pipeline.events.EventManager
import dev.rubentxu.pipeline.events.StartEvent
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.steps.Configurable
import dev.rubentxu.pipeline.steps.EnvVars
import kotlinx.coroutines.coroutineScope
import java.nio.file.Path
import kotlin.system.measureTimeMillis

/**
 * `Pipeline` class defines a domain-specific language (DSL) for creating and configuring
 * a pipeline. The pipeline is designed to work with any available agent and
 * allows for custom environment variables and stages.
 *
 * @property logger The logger used for outputting pipeline logs.
 */
@PipelineDsl
class Pipeline(val logger: PipelineLogger) : Configurable {

    /**
     * A list of stages to be executed by this pipeline.
     */
    private var stagesList = mutableListOf<Stage>()

    /**
     * The name of the current stage being executed.
     */
    var currentStage: String = "initial pipeline"


    /**
     * Represents any available agent in the pipeline.
     */
    var agent: Agent = AnyAgent()

    /**
     * The environment variables for the pipeline.
     */
    val env = EnvVars()

    /**
     * The working directory for this pipeline, defaulting to the user's current directory.
     */
    internal val workingDir: Path = Path.of(System.getProperty("user.dir"))

    var stageResults = mutableListOf<StageResult>()


    /**
     * A block of code to execute after all stages have been executed.
     */
    var post: Post

    init {
        logger.system("Pipeline initialized")
        post = Post(getPipeline())
    }


    /**
     * This function registers an event with the event manager.
     *
     * @param event The event to register.
     */
    suspend fun registerEvent(event: Event) {
        logger.system("Registering event: $event")
        EventManager.notify(event)

    }

    /**
     * Returns the instance of the current pipeline.
     *
     * @return The current `PipelineDsl` instance.
     */
    fun getPipeline(): Pipeline {
        return this
    }


    /**
     * This function sets the agent for the pipeline to any available agent.
     *
     * @param agentParam Placeholder for the any available agent.
     */
    suspend fun agent(block: AgentBlock.() -> Agent) {
        logger.system("Running pipeline using any available agent... ")
        val agentBlock = AgentBlock()
        agent = agentBlock.block()

    }

    /**
     * This function sets the environment variables for the pipeline.
     *
     * @param block A block of code to define the environment variables.
     */
    suspend fun environment(block: EnvVars.() -> Unit) {
        env.apply(block)
    }

    /**
     * This function defines the stages for the pipeline.
     *
     * @param block A block of code to define the stages.
     * @return A List<StageResult> representing the results of each stage.
     */
    suspend fun stages(block: StagesDsl.() -> Unit) {


        val dsl = StagesDsl()
        dsl.block()
        stagesList = dsl.stages
    }

    /**
     * Executes all the stages defined in the pipeline and returns the results.
     *
     * @return A list of results of each stage.
     */
    suspend fun executeStages(): List<StageResult>  {
        val results = stagesList.map { stage ->
            var status = Status.Success

            currentStage = stage.name
            registerEvent(StartEvent(currentStage!!, System.currentTimeMillis()))
            val time = measureTimeMillis {
                try {
                    stage.run(getPipeline())
                } catch (e: Exception) {
                    status = Status.Failure
                    logger.error("Error running stage $currentStage, ${e.message}")
                    throw e
                }
            }

            registerEvent(EndEvent(currentStage!!, System.currentTimeMillis(), time, status))

            StageResult(stage.name, status).also { stageResults.add(it) }
        }
        val steps = StepBlock(this@Pipeline)
        logger.system("Pipeline finished with status: ${results.map { it.status }}")
        if (results.any { it.status == Status.Failure }) {
            post.failureFunc.invoke(steps)
        } else {
            post.successFunc.invoke(steps)
        }

        post.alwaysFunc.invoke(steps)


        return results
    }

    /**
     * Configures this Pipeline based on the provided configuration map.
     *
     * @param configuration The configuration map.
     */
    override fun configure(configuration: Map<String, kotlin.Any>) {
        TODO("Not yet implemented")
    }

    /**
     * Converts a relative path to a full path with respect to the StepBlock's working directory.
     * If the provided path is already absolute, it is returned as is.
     *
     * @param workingDir The path to convert to a full path.
     * @return The full path as a string.
     */
    fun toFullPath(workingDir: Path): String {
        return if (workingDir.isAbsolute) {
            workingDir.toString()
        } else {
            "${this.workingDir}/${workingDir}"
        }
    }


    /**
     * Defines a block of code to execute after all stages have been executed.
     *
     * @param block The block of code to execute.
     */
    suspend fun post(block: Post.() -> Unit) {
        post = Post(getPipeline()).apply(block)

    }


}






