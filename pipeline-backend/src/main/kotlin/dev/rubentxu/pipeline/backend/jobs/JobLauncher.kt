package dev.rubentxu.pipeline.backend.jobs

import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.logger.IPipelineLogger
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.model.steps.EnvVars
import kotlinx.coroutines.*

class JobLauncherImpl(
    override val listeners: MutableList<JobExecutionListener> = mutableListOf(),
    private val logger: IPipelineLogger = PipelineLogger.getLogger(),
) : JobLauncher, CoroutineScope by CoroutineScope(Dispatchers.Default) {


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
     * @return A PipelineResult instance containing the results of the pipeline
     *     execution.
     */


    override fun launch(instance: JobDefinition, context: IPipelineContext): JobExecution {
        val startSignal = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            try {
                val pipeline = instance.resolvePipeline(context)
                logger.system("Build Pipeline: $pipeline")
                startSignal.await()

                val preExecuteJobs = listeners.map { listener ->
                    async { listener.onPreExecute(pipeline) }
                }

                val result = execute(pipeline, context)

                // Wait for all preExecute jobs to complete
                preExecuteJobs.forEach { it.await() }

                val postExecuteJobs = listeners.map { listener ->
                    async { listener.onPostExecute(pipeline, result) }
                }

                // Wait for all postExecute jobs to complete
                postExecuteJobs.forEach { it.await() }

            } catch (e: Exception) {
                handleScriptExecutionException(e)
            }
        }
        val jobExecution = JobExecution(job)
        listeners.add(jobExecution)
        startSignal.complete(Unit)
        return jobExecution
    }

    override suspend fun execute(pipeline: IPipeline, context: IPipelineContext): JobResult = coroutineScope {
        var status: Status

        logger.system("Registering pipeline listeners...")
        logger.system("Executing pipeline...")

        try {
            pipeline.executeStages(context)
        } catch (e: Exception) {
            logger.error("Pipeline execution failed: ${e.message}")
            status = Status.Failure
            pipeline.stageResults.addAll(listOf(StageResult(pipeline.currentStage, status)))
        }

        logger.system("Pipeline execution finished")

        status = if (pipeline.stageResults.any { it.status == Status.Failure }) Status.Failure else Status.Success

        val env = context.getResource(EnvVars::class).getOrThrow()
        val result = JobResult(status, pipeline.stageResults, env, logger.logs())

        return@coroutineScope result
    }

    // Maneja las excepciones ocurridas durante la ejecución del script.
    fun handleScriptExecutionException(exception: Exception, showStackTrace: Boolean = true) {
        val logger: PipelineLogger = PipelineLogger.getLogger() as PipelineLogger
        val regex = """ERROR (.*) \(.*:(\d+):(\d+)\)""".toRegex()
        val match = regex.find(exception.message ?: "")

        if (match != null) {
            val (error, line, space) = match.destructured
            logger.errorBanner(listOf("Error in Pipeline definition: $error", "Line: $line", "Space: $space"))
        } else {
            logger.error("Error in Pipeline definition: ${exception.message}")
        }

        // Imprime el stacktrace completo si el flag está activado.
        if (showStackTrace) {
            exception.printStackTrace()
        }
    }


}
//
//interface JobScheduler {
//    fun scheduleJob(job: JobDefinition, trigger: Trigger): JobExecution
//    fun cancelScheduledJob(jobExecution: JobExecution)
//    fun getScheduledJobStatus(jobExecution: JobExecution): JobStatus
//}
//
//class CoroutineJobScheduler : JobScheduler {
//    private val scheduledJobs = mutableMapOf<JobExecution, Job>()
//
//    override fun scheduleJob(job: JobDefinition, trigger: Trigger): JobExecution {
//        val jobExecution = JobExecution(job)
//        val coroutineJob = GlobalScope.launch {
//            while (isActive) {
//                delay(trigger.nextExecutionTime())
//                jobExecution.execute()
//            }
//        }
//        scheduledJobs[jobExecution] = coroutineJob
//        return jobExecution
//    }
//
//    override fun cancelScheduledJob(jobExecution: JobExecution) {
//        scheduledJobs[jobExecution]?.cancel()
//        scheduledJobs.remove(jobExecution)
//    }
//
//    override fun getScheduledJobStatus(jobExecution: JobExecution): JobStatus {
//        return if (scheduledJobs[jobExecution]?.isActive == true) {
//            JobStatus.SCHEDULED
//        } else {
//            JobStatus.NOT_SCHEDULED
//        }
//    }
//}
//
//enum class JobStatus {
//    SCHEDULED,
//    NOT_SCHEDULED
//
//}
