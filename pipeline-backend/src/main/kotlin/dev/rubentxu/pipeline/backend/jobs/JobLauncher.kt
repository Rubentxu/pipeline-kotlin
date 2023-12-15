package dev.rubentxu.pipeline.backend.jobs


import dev.rubentxu.pipeline.backend.buildPipeline
import dev.rubentxu.pipeline.backend.evaluateScriptFile
import dev.rubentxu.pipeline.backend.handleScriptExecutionException
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.model.repository.ISourceCodeManager
import kotlinx.coroutines.*
import java.net.URL
import java.nio.file.Path


class JobLauncherImpl(
    override val listeners: MutableList<JobExecutionListener> = mutableListOf(),
    val sourceCodeRepositoryManager: ISourceCodeManager,
) : JobLauncher, CoroutineScope by CoroutineScope(Dispatchers.Default) {

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



    override fun launch(instance: JobDefinition): JobExecution {
        val startSignal = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            try {
                val pipeline = resolvePipeline(instance)
                logger.system("Build Pipeline: $pipeline")
                startSignal.await()

                val preExecuteJobs = listeners.map { listener ->
                    async { listener.onPreExecute(pipeline) }
                }

                val result = execute(pipeline)

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
        val jobExecution =  JobExecution(job)
        listeners.add(jobExecution)
        startSignal.complete(Unit)
        return jobExecution
    }

    suspend fun execute(pipeline: IPipeline): JobResult = coroutineScope {
        var status: Status

        logger.system("Registering pipeline listeners...")
        logger.system("Executing pipeline...")

        try {
            pipeline.executeStages()
        } catch (e: Exception) {
            logger.error("Pipeline execution failed: ${e.message}")
            status = Status.Failure
            pipeline.stageResults.addAll(listOf(StageResult(pipeline.currentStage, status)))
        }

        logger.system("Pipeline execution finished")

        status = if (pipeline.stageResults.any { it.status == Status.Failure }) Status.Failure else Status.Success

        val result = JobResult(status, pipeline.stageResults, pipeline.env, logger.logs())

        return@coroutineScope result
    }

    fun resolvePipeline(job: JobDefinition): Pipeline {
        val smcReferenceId = job.pipelineFileSource.scmReferenceId
        val relativeScriptPath = job.pipelineFileSource.relativeScriptPath

        val repository = sourceCodeRepositoryManager.findSourceRepository(smcReferenceId)
        val sourceCode = repository.retrieve()
        // url to path
        val scriptPath: Path = resolveScriptPath(sourceCode.url, relativeScriptPath)


        val pipelineDef = evaluateScriptFile(scriptPath.toString())
        logger.system("Pipeline definition: $pipelineDef")
        return buildPipeline(pipelineDef)
    }


    private fun resolveScriptPath(url: URL, relativeScriptPath: Path): Path {
        val rootPath = Path.of(url.path)
        return rootPath.resolve(relativeScriptPath)
    }



}