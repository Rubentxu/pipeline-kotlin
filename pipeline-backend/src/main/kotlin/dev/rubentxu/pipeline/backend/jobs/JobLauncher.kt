package dev.rubentxu.pipeline.backend.jobs


import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking


class JobLauncherImpl(
    override val listeners: MutableList<JobExecutionListener> = mutableListOf(),
    override val agent: Agent
) : JobLauncher {

    private val logger = PipelineLogger.getLogger()


    /**
     * Adds a listener to the pipeline executor.
     *
     * @param listener The listener to add.
     */
    fun addListener(listener: JobExecutionListener) {
        listeners.add(listener)
    }

    /**
     * Executes a PipelineDefinition and returns the result.
     *
     * @param pipeline The Pipeline to execute.
     * @return A PipelineResult instance containing the results of the pipeline execution.
     */
    override fun execute(pipeline: Pipeline): JobResult {
        var status: Status

        logger.system("Create handler for pipeline exceptions...")
        val pipelineExceptionHandler = CoroutineExceptionHandler { _, exception ->
            logger.error("Pipeline execution failed: ${exception.message}")
            status = Status.Failure
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

            status = if (pipeline.stageResults.any { it.status == Status.Failure }) Status.Failure else Status.Success

            val result = JobResult(status, pipeline.stageResults, pipeline.env, logger.logs())


            val postExecuteJobs = listeners.map { listener ->
                async(Dispatchers.Default) { listener.onPostExecute(pipeline, result) }
            }

            // Wait for all postExecute jobs to complete
            postExecuteJobs.forEach { it.await() }
            return@runBlocking result
        }

    }

    override fun launch(instance: JobInstance): JobExecution {
        val pipeline = instance.abstractPipelineLoader.loadPipeline()
        val result = execute(pipeline)
        return JobExecution(instance, result)
    }


}