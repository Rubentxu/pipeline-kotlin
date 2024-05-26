package dev.rubentxu.pipeline.core.pipeline

import dev.rubentxu.pipeline.core.events.EventStore
import dev.rubentxu.pipeline.core.events.PipelineEvent
import dev.rubentxu.pipeline.core.interfaces.ILogger
import dev.rubentxu.pipeline.core.interfaces.IPipeline
import dev.rubentxu.pipeline.core.interfaces.IPipelineContext
import dev.rubentxu.pipeline.core.jobs.StageResult
import dev.rubentxu.pipeline.core.jobs.Status
import dev.rubentxu.pipeline.core.dsl.StageExecutor
import kotlin.system.measureTimeMillis

interface PipelineDomain

interface IPipelineConfig : PipelineDomain

open class PipelineError(message: String) : Exception(message)


/**
 * Data class for validation errors.
 *
 * @property message The error message. This is used to represent an error
 *     that occurs during validation.
 */
data class PropertiesError(override val message: String) : PipelineError(message)

data class NoSuchServiceError(override val message: String) : PipelineError(message)


interface PipelineDomainDslFactory<T : PipelineDomain> {
    suspend fun create(block: T.() -> Unit): PipelineDomain
}


/**
 * `Pipeline` class defines a domain-specific language (DSL) for creating
 * and configuring a pipeline. The pipeline is designed to work with any
 * available agent and allows for custom environment variables and stages.
 *
 * @property logger The logger used for outputting pipeline logs.
 */

class Pipeline(
    val stages: List<StageExecutor>,
    val postExecution: PostExecution,
    val logger: ILogger,
    val eventStore: EventStore,
    override var currentStage: String = "initial pipeline",
    override var stageResults: MutableList<StageResult> = mutableListOf(),
) : IPipeline {


    /**
     * This function registers an event with the event manager.
     *
     * @param event The event to register.
     */
    suspend fun registerEvent(event: PipelineEvent) {
        eventStore.publishEvent(event)
    }


    /**
     * Executes all the stages defined in the pipeline and returns the results.
     *
     * @return A list of results of each stage.
     */
    override suspend fun executeStages(context: IPipelineContext) {
        for (stage in stages) {
            var status = Status.Success

            currentStage = stage.name
//            registerEvent(StartEvent(currentStage!!, System.currentTimeMillis()))
            val time = measureTimeMillis {
                try {
                    stage.run(context)
                } catch (e: Exception) {
                    status = Status.Failure
                    logger.error("Pipeline", "Abort pipeline stages in stage $currentStage, ${e.message}")
                }
            }

            stageRegister(time, status, stage)
            if (status == Status.Failure) {
                break
            }
        }
        postExecution.run(context, stageResults)

    }

    private suspend fun stageRegister(
        time: Long,
        status: Status,
        stage: StageExecutor,
    ): StageResult {
//        registerEvent(EndEvent(currentStage!!, System.currentTimeMillis(), time, status))

        return StageResult(stage.name, status).also { stageResults.add(it) }
    }


}