package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.logger.LogLevel
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.steps.EnvVars
import kotlinx.coroutines.*


@DslMarker
annotation class PipelineDsl

/**
 * Enum representing the status of a pipeline or stage.
 */
enum class Status {
    Success,
    Failure,
    Unstable,
    Aborted,
    NotBuilt
}

/**
 * Data class representing the result of a pipeline stage.
 *
 * @property name The name of the stage.
 * @property status The status of the stage (success or failure).
 * @property output The output from the steps in the stage.
 */
data class StageResult(
    val name: String,
    val status: Status,
    val output: String = "",
    val error: String = ""

)

/**
 * Data class representing the result of a Jenkins pipeline execution.
 *
 * @property status The overall status of the pipeline (success or failure).
 * @property stageResults The results of each individual stage in the pipeline.
 * @property env The state of the environment variables at the end of the pipeline.
 */
data class PipelineResult(
    val status: Status,
    val stageResults: List<StageResult>,
    val env: EnvVars,
    val logs: MutableList<String>
)

/**
 * Executes a block of DSL code within a new Jenkins pipeline and returns the result.
 *
 * This function creates a new pipeline and runs a block of code in it.
 *  *
 *  * @param block A block of code to run in the pipeline.
 *  * @return A PipelineResult instance containing the results of the pipeline execution.
 *  */
fun pipeline(block: suspend Pipeline.() -> Unit): PipelineDefinition {
    return PipelineDefinition(block)
}

class PipelineDefinition(val block: suspend Pipeline.() -> Unit) {
    suspend fun build(logger: PipelineLogger): Pipeline {
        val pipeline = Pipeline(logger)
        pipeline.block()
        return pipeline
    }

    suspend fun build(logLevel: LogLevel = LogLevel.DEBUG): Pipeline {
        val logger = PipelineLogger(logLevel)
        val pipeline = Pipeline(logger)
        pipeline.block()
        return pipeline
    }

}


interface PipelineListener {
    suspend fun onPreExecute(pipeline: Pipeline)
    suspend fun onPostExecute(pipeline: Pipeline, result: PipelineResult)
}

class PipelineExecutor {

    val listeners = mutableListOf<PipelineListener>()

    /**
     * Adds a listener to the pipeline executor.
     *
     * @param listener The listener to add.
     */
    fun addListener(listener: PipelineListener) {
        listeners.add(listener)
    }

    /**
     * Executes a PipelineDefinition and returns the result.
     *
     * @param pipeline The Pipeline to execute.
     * @return A PipelineResult instance containing the results of the pipeline execution.
     */
    fun execute(pipeline: Pipeline): PipelineResult = runBlocking {
        executePipeline(pipeline)
    }

    private suspend fun executePipeline(pipeline: Pipeline): PipelineResult = supervisorScope {
        var status: Status
        val logger = pipeline.logger

        logger.system("Create handler for pipeline exceptions...")
        val pipelineExceptionHandler = CoroutineExceptionHandler { _, exception ->
            logger.error("Pipeline execution failed: ${exception.message}")
            status = Status.Failure
            pipeline.stageResults.addAll(listOf(StageResult(pipeline.currentStage, status)))
        }

        logger.system("Registering pipeline listeners...")
        val preExecuteJobs = listeners.map { listener ->
            async(Dispatchers.Default) { listener.onPreExecute(pipeline) }
        }

        withContext(Dispatchers.Default + pipelineExceptionHandler) {
            logger.system("Executing pipeline...")
            pipeline.executeStages()
            logger.system("Pipeline execution finished")
        }

        status = if (pipeline.stageResults.any { it.status == Status.Failure }) Status.Failure else Status.Success

        val result = PipelineResult(status, pipeline.stageResults, pipeline.env, pipeline.logger.logs)

        // Wait for all preExecute jobs to complete
        preExecuteJobs.forEach { it.await() }

        val postExecuteJobs = listeners.map { listener ->
            async(Dispatchers.Default) { listener.onPostExecute(pipeline, result) }
        }

        // Wait for all postExecute jobs to complete
        postExecuteJobs.forEach { it.await() }

        return@supervisorScope result
    }


}