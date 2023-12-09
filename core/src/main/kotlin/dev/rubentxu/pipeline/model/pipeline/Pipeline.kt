package dev.rubentxu.pipeline.model.pipeline

import dev.rubentxu.pipeline.events.EndEvent
import dev.rubentxu.pipeline.events.Event
import dev.rubentxu.pipeline.events.EventManager
import dev.rubentxu.pipeline.events.StartEvent
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.steps.Configurable
import dev.rubentxu.pipeline.steps.EnvVars
import java.nio.file.Path
import kotlin.system.measureTimeMillis

/**
 * `Pipeline` class defines a domain-specific language (DSL) for creating and configuring
 * a pipeline. The pipeline is designed to work with any available agent and
 * allows for custom environment variables and stages.
 *
 * @property logger The logger used for outputting pipeline logs.
 */

class Pipeline(
    val agent: Agent,
    val stages: List<StageExecutor>,
    val env: EnvVars,
    val postExecution: PostExecution
) : Configurable {

    private val logger = PipelineLogger.getLogger()

    /**
     * The name of the current stage being executed.
     */
    var currentStage: String = "initial pipeline"

    /**
     * The working directory for this pipeline, defaulting to the user's current directory.
     */
    val workingDir: Path = Path.of(System.getProperty("user.dir"))

    var stageResults = mutableListOf<StageResult>()


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
     * Executes all the stages defined in the pipeline and returns the results.
     *
     * @return A list of results of each stage.
     */
    suspend fun executeStages() {
        for (stage in stages) {
            var status = Status.Success

            currentStage = stage.name
            registerEvent(StartEvent(currentStage!!, System.currentTimeMillis()))
            val time = measureTimeMillis {
                try {
                    stage.run(this)
                } catch (e: Exception) {
                    status = Status.Failure
                    logger.error("Abort pipeline stages in stage $currentStage, ${e.message}")
                }
            }

            stageRegister(time, status, stage)
            if (status == Status.Failure) {
                break
            }
        }
        postExecution.run(this, stageResults)

    }

    private suspend fun stageRegister(
        time: Long,
        status: Status,
        stage: StageExecutor
    ): StageResult {
        registerEvent(EndEvent(currentStage!!, System.currentTimeMillis(), time, status))

        return StageResult(stage.name, status).also { stageResults.add(it) }
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


}






