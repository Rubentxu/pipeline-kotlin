package dev.rubentxu.pipeline.backend.jobs


import dev.rubentxu.pipeline.backend.handleScriptExecutionException
import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.*
import kotlinx.coroutines.*


class JobLauncherImpl(
    override val listeners: MutableList<JobExecutionListener> = mutableListOf(),
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
                val pipeline = instance.resolvePipeline()
                logger.system("Build Pipeline: $pipeline")
                startSignal.await()

                val preExecuteJobs = listeners.map { listener ->
                    async { listener.onPreExecute(pipeline) }
                }

                val result = instance.execute(pipeline)

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



}