package dev.rubentxu.pipeline.model.job

import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import dev.rubentxu.pipeline.model.pipeline.StageResult
import dev.rubentxu.pipeline.model.pipeline.Status
import dev.rubentxu.pipeline.model.pipeline.interfaces.PipelineListener
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking


class JobExecutor {

    private val logger = PipelineLogger.getLogger()

    private val listeners = mutableListOf<PipelineListener>()

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
    fun execute(pipeline: Pipeline): PipelineResult {
        var status: Status

        logger.system("Create handler for pipeline exceptions...")
        val pipelineExceptionHandler = CoroutineExceptionHandler { _, exception ->
            logger.error("Pipeline execution failed: ${exception.message}")
            status = Status.FAILURE
            pipeline.stageResults.addAll(listOf(StageResult(pipeline.currentStage, status)))
        }

        logger.system("Registering pipeline listeners...")


        return runBlocking(Dispatchers.Default + pipelineExceptionHandler) {
            val preExecuteJobs = listeners.map { listener ->
                async(Dispatchers.Default) { listener.onPreExecute(pipeline) }
            }

            logger.system("Executing pipeline...")
            pipeline.executeStages()
            logger.system("Pipeline execution finished")

            // Wait for all preExecute jobs to complete
            preExecuteJobs.forEach { it.await() }

            status = if (pipeline.stageResults.any { it.status == Status.FAILURE }) Status.FAILURE else Status.SUCCESS

            val result = PipelineResult(status, pipeline.stageResults, pipeline.env, logger.logs())


            val postExecuteJobs = listeners.map { listener ->
                async(Dispatchers.Default) { listener.onPostExecute(pipeline, result) }
            }

            // Wait for all postExecute jobs to complete
            postExecuteJobs.forEach { it.await() }
            return@runBlocking result
        }

    }


}