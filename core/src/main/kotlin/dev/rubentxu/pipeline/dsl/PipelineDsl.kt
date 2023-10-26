package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.steps.Configurable
import dev.rubentxu.pipeline.steps.EnvVars
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.Path

/**
 * `PipelineDsl` class defines a domain-specific language (DSL) for creating and configuring
 * a pipeline. The pipeline is designed to work with any available agent and
 * allows for custom environment variables and stages.
 */
class PipelineDsl: Configurable{

    internal val logger  = PipelineLogger("INFO")

    /**
     * Represents any available agent in the pipeline.
     */
    val any = Placeholder.ANY

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
     * Returns the instance of the current pipeline.
     *
     * @return The current `PipelineDsl` instance.
     */
    fun getPipeline(): PipelineDsl {
        return this
    }

    /**
     * This function sets the agent for the pipeline to any available agent.
     *
     * @param any Placeholder for the any available agent.
     */
    suspend fun agent(any: Placeholder) {
        logger.debug("Running pipeline using any available agent... ${any}")
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
    suspend fun stages(block: StagesDsl.() -> Unit): List<StageResult> {
        val dsl = StagesDsl()
        dsl.block()

        return dsl.stages.map { stage ->
            var status = Status.Success
            try {
                stage.run(getPipeline())
            } catch (e: Exception) {
                status = Status.Failure
            }
            StageResult(stage.name, status).also { stageResults.add(it) }
        }
    }


    suspend fun stagesAsync(block: StagesDsl.() -> Unit): List<StageResult> {
        val dsl = StagesDsl()
        dsl.block()

        return coroutineScope {
            dsl.stages.map { stage ->
                async {
                    var status = Status.Success
                    try {
                        stage.run(getPipeline())
                    } catch (e: Exception) {
                        status = Status.Failure
                    }
                    StageResult(stage.name, status)
                }
            }.awaitAll().also { stageResults.addAll(it) }
        }
    }

    /**
     * A placeholder for any available agent.
     */
    enum class Placeholder {
        ANY
    }

    /**
     * Configures this Pipeline based on the provided configuration map.
     *
     * @param configuration The configuration map.
     */
    override fun configure(configuration: Map<String, Any>) {
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
}






