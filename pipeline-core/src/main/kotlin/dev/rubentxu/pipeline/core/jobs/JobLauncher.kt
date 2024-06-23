package dev.rubentxu.pipeline.core.jobs


import dev.rubentxu.pipeline.core.interfaces.*
import dev.rubentxu.pipeline.core.jobs.*
import dev.rubentxu.pipeline.core.pipeline.PipelineBuilder
import dev.rubentxu.pipeline.core.pipeline.PipelineError
import kotlinx.coroutines.*
import java.io.InputStreamReader
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

class JobLauncherImpl(
    private val logger: ILogger,
    override val listeners: MutableList<JobExecutionListener> = mutableListOf(),
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
    override fun launch(context: IPipelineContext, scriptReader: InputStreamReader): JobExecution {
        val startSignal = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            try {
                val pipeline: IPipeline = getPipeline(scriptReader, context)
                logger.system("JobLauncher", "Build Pipeline: $pipeline")
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

        val jobExecution = JobExecution(job, logger)
        listeners.add(jobExecution)
        startSignal.complete(Unit)
        return jobExecution
    }

    override suspend fun execute(pipeline: IPipeline, context: IPipelineContext): JobResult = coroutineScope {
        var status: Status

        logger.system("JobLauncher", "Registering pipeline listeners...")
        logger.system("JobLauncher", "Executing pipeline...")

        try {
            pipeline.executeStages(context)
        } catch (e: Exception) {
            logger.error("JobLauncher", "Pipeline execution failed: ${e.message}")
            status = Status.Failure
            pipeline.stageResults.addAll(listOf(StageResult(pipeline.currentStage, status)))
        }

        logger.system("JobLauncher", "Pipeline execution finished")

        status = if (pipeline.stageResults.any { it.status == Status.Failure }) Status.Failure else Status.Success

        val env = context.getEnvVars()
        val result = JobResult(status, pipeline.stageResults, env)

        return@coroutineScope result
    }

    // Maneja las excepciones ocurridas durante la ejecución del script.
    fun handleScriptExecutionException(exception: Exception, showStackTrace: Boolean = true) {

        val regex = """ERROR (.*) \(.*:(\d+):(\d+)\)""".toRegex()
        val match = regex.find(exception.message ?: "")

        if (match != null) {
            val (error, line, space) = match.destructured
            logger.logPrettyError(
                "JobLauncher",
                listOf("Error in Pipeline definition: $error", "Line: $line", "Space: $space")
            )
        } else {
            logger.error("JobLauncher", "Error in Pipeline definition: ${exception.message}")
        }

        // Imprime el stacktrace completo si el flag está activado.
        if (showStackTrace) {
            exception.printStackTrace()
        }
    }

    fun getPipeline(scriptReader: InputStreamReader, context: IPipelineContext): IPipeline {
        val pipelineDef = evaluateScript(scriptReader)
        logger.system("JobInstance", "Pipeline definition: $pipelineDef")
        return buildPipeline(pipelineDef, context)
    }

    fun buildPipeline(pipelineDef: PipelineBuilder, context: IPipelineContext): IPipeline = runBlocking {
        pipelineDef.build(context)
    }

    // Evalúa el archivo de script y devuelve la definición del pipeline.
    fun evaluateScript(scriptReader: InputStreamReader): PipelineBuilder {
        val engine = getScriptEngine()
//        val scriptFile = scriptPath.toFile().reader()
        return engine.eval(scriptReader) as? PipelineBuilder
            ?: throw PipelineError("Script does not contain a PipelineDefinition")
    }

    fun getScriptEngine(): ScriptEngine =
        ScriptEngineManager().getEngineByExtension("kts")
            ?: throw IllegalStateException("Script engine for .kts files not found")


}

