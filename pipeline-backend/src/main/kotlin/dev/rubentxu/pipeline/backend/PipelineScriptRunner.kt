package dev.rubentxu.pipeline.backend

import dev.rubentxu.pipeline.backend.agent.docker.ContainerLifecycleManager
import dev.rubentxu.pipeline.backend.agent.docker.DockerConfigManager
import dev.rubentxu.pipeline.backend.agent.docker.DockerImageBuilder
import dev.rubentxu.pipeline.logger.LogLevel
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.logger.SocketLogConfigurationStrategy
import dev.rubentxu.pipeline.model.CascManager
import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.model.job.JobExecutor
import dev.rubentxu.pipeline.model.pipeline.*
import dev.rubentxu.pipeline.steps.EnvVars
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager


class PipelineScriptRunner {

    companion object {
        @JvmStatic
        fun evalWithScriptEngineManager(
            scriptPath: String,
            configPath: String,
            jarLocation: File = File(PipelineScriptRunner::class.java.protectionDomain.codeSource.location.toURI()),
            logger: PipelineLogger = PipelineLogger(
                logLevel = LogLevel.TRACE,
                logConfigurationStrategy = SocketLogConfigurationStrategy()
            ),
        ): PipelineResult {

            val pipelineExecutable = Path.of("", "pipeline-kts").toAbsolutePath().toFile()
            logger.info("Pipeline executable: ${pipelineExecutable.absolutePath}")
            logger.info("Pipeline executable exists: ${pipelineExecutable.exists()}")

            logger.info("JAR location: ${jarLocation.absolutePath}")
            val executablePath =
                if (pipelineExecutable.exists()) pipelineExecutable.absolutePath else jarLocation.absolutePath
            logger.info("Resolve executable path: $executablePath")

            return try {
                val pipelineDef = evaluateScriptFile(scriptPath)
                logger.system("Pipeline definition: $pipelineDef")


                val configurationResult: Result<PipelineConfig> =
                    CascManager().resolveConfig(normalizeAndAbsolutePath(configPath))

                if (configurationResult.isFailure) {
                    logger.error("Error reading config file: ${configurationResult.exceptionOrNull()?.message}")
                    return PipelineResult(Status.FAILURE, emptyList(), EnvVars(mapOf()), mutableListOf())
                }
                val configuration = configurationResult.getOrThrow()

                val pipeline = buildPipeline(pipelineDef, configuration)
                logger.system("Build Pipeline: $pipeline")
                val listOfPaths = listOf(scriptPath, configPath, executablePath).map { normalizeAndAbsolutePath(it) }
                executePipeline(pipeline, configuration, listOfPaths, logger)

            } catch (e: Exception) {
                handleScriptExecutionException(e, logger)
                PipelineResult(Status.FAILURE, emptyList(), EnvVars(mapOf()), mutableListOf())
            }
        }
    }
}


fun getScriptEngine(): ScriptEngine =
    ScriptEngineManager().getEngineByExtension("kts")
        ?: throw IllegalStateException("Script engine for .kts files not found")

// Evalúa el archivo de script y devuelve la definición del pipeline.
fun evaluateScriptFile(scriptPath: String): PipelineDefinition {
    val engine = getScriptEngine()
    val scriptFile = normalizeAndAbsolutePath(scriptPath).toFile()
    if (!scriptFile.exists()) {
        throw IllegalArgumentException("Script file ${scriptPath} does not exist")
    }
    val result =  engine.eval(scriptFile.reader()) as? PipelineDefinition
    if (result != null) {
        return result
    }
    throw IllegalArgumentException("Script does not contain a PipelineDefinition")
}

// Procesa la definición del pipeline después de la evaluación.
fun executePipeline(
    pipeline: Pipeline,
    configuration: PipelineConfig,
    listOfPaths: List<Path>,
    logger: PipelineLogger,
): PipelineResult {


    val isAgentEnv = System.getenv("IS_AGENT")
    logger.system("Env isAgent: $isAgentEnv")
    // si pipeline.agent no es AnyAgent se ejecuta en un agente
    if (!(pipeline.agent is AnyAgent) && isAgentEnv == null) {
        return executeWithAgent(pipeline, configuration, listOfPaths)
    }

    return JobExecutor().execute(pipeline)
}

// Construye el pipeline usando coroutines.
fun buildPipeline(pipelineDef: PipelineDefinition, configuration: IPipelineConfig): Pipeline = runBlocking {
    pipelineDef.build(configuration)
}

// Maneja las excepciones ocurridas durante la ejecución del script.
fun handleScriptExecutionException(exception: Exception, logger: PipelineLogger, showStackTrace: Boolean = true) {
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

fun executeWithAgent(pipeline: Pipeline, config: PipelineConfig, paths: List<Path>): PipelineResult {
    val agent = pipeline.agent
    val logger = PipelineLogger.getLogger()

    if (agent is DockerAgent) {
        logger.info("Docker image: ${agent.image}")
        logger.info("Docker tag: ${agent.tag}")
        return executeInDockerAgent(agent, config, paths)


    } else if (agent is KubernetesAgent) {
        logger.info("Kubernetes yalm: ${agent.yaml}")
        logger.info("Kubernetes label: ${agent.label}")
//        return executeInKubernetesAgent(agent, config, paths)

    }
    return PipelineResult(Status.FAILURE, emptyList(), EnvVars(mapOf()), mutableListOf())
}

fun executeInDockerAgent(agent: DockerAgent, config: PipelineConfig, paths: List<Path>): PipelineResult {
    val dockerClientProvider = DockerConfigManager(agent)
    val imageBuilder = DockerImageBuilder(dockerClientProvider)
    val containerManager = ContainerLifecycleManager(dockerClientProvider)

    val imageId = imageBuilder.buildCustomImage("${agent.image}:${agent.tag}", paths)
    containerManager.createAndStartContainer(mapOf("IS_AGENT" to "true"))
    return PipelineResult(Status.SUCCESS, emptyList(), EnvVars(mapOf()), mutableListOf())
}


fun normalizeAndAbsolutePath(file: String): Path {
    return Path.of(file).toAbsolutePath().normalize()
}

fun normalizeAndAbsolutePath(path: Path): Path {
    return path.toAbsolutePath().normalize()
}

