package dev.rubentxu.pipeline.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import dev.rubentxu.pipeline.backend.agent.ContainerManager
import dev.rubentxu.pipeline.cli.PipelineCliCommand
import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.LogLevel
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.logger.SocketLogConfigurationStrategy
import dev.rubentxu.pipeline.steps.EnvVars
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Path
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager


fun evalWithScriptEngineManager(scriptPath: String,
                                configPath: String,
                                jarLocation: File = File(PipelineCliCommand::class.java.protectionDomain.codeSource.location.toURI())): PipelineResult {
    val logger = PipelineLogger(logLevel = LogLevel.TRACE, logConfigurationStrategy = SocketLogConfigurationStrategy())

    val pipelineExecutable = Path.of("", "pipeline-kts").toAbsolutePath().toFile()
    logger.info("Pipeline executable: ${pipelineExecutable.absolutePath}")
    logger.info("Pipeline executable exists: ${pipelineExecutable.exists()}")

    logger.info("JAR location: ${jarLocation.absolutePath}")
    val resolveExecutablePath = if(pipelineExecutable.exists()) pipelineExecutable.absolutePath  else jarLocation.absolutePath
    logger.info("Resolve executable path: $resolveExecutablePath")

    return try {
        val pipelineDef = evaluateScriptFile(scriptPath)
        val pipeline = buildPipeline(pipelineDef, logger)
        executePipeline(pipeline, scriptPath, configPath, resolveExecutablePath, logger)
    } catch (e: Exception) {
        handleScriptExecutionException(e, logger)
        PipelineResult(Status.Failure, emptyList(), EnvVars(), mutableListOf())
    }
}

fun getScriptEngine(): ScriptEngine =
    ScriptEngineManager().getEngineByExtension("kts")
        ?: throw IllegalStateException("Script engine for .kts files not found")

// Evalúa el archivo de script y devuelve la definición del pipeline.
fun evaluateScriptFile(scriptPath: String): PipelineDefinition {
    val engine = getScriptEngine()
    val scriptFile = normalizeAndAbsolutePath(scriptPath).toFile()
    return engine.eval(scriptFile.reader()) as? PipelineDefinition
        ?: throw IllegalArgumentException("Script does not contain a PipelineDefinition")
}

// Procesa la definición del pipeline después de la evaluación.
fun executePipeline(
    pipeline: Pipeline,
    scriptPath: String,
    configPath: String,
    executablePath: String,
    logger: PipelineLogger
): PipelineResult {

    val listOfPaths = listOf(scriptPath, configPath, executablePath).map { normalizeAndAbsolutePath(it) }
    val configuration = readConfigFile(normalizeAndAbsolutePath(configPath).toString())

    val isAgentEnv = System.getenv("IS_AGENT")
    logger.system("Env isAgent: $isAgentEnv")
    // si pipeline.agent no es AnyAgent se ejecuta en un agente
    if(!(pipeline.agent is AnyAgent) && isAgentEnv == null) {
        return executeWithAgent(pipeline, configuration, listOfPaths)
    }

    return PipelineExecutor().execute(pipeline)
}

// Construye el pipeline usando coroutines.
fun buildPipeline(pipelineDef: PipelineDefinition, logger: PipelineLogger): Pipeline = runBlocking {
    pipelineDef.build(logger)
}

// Maneja las excepciones ocurridas durante la ejecución del script.
fun handleScriptExecutionException(exception: Exception, logger: PipelineLogger, showStackTrace: Boolean = true) {
    val regex = """ERROR (.*) \(ScriptingHost:(\d+):(\d+)\)""".toRegex()
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

fun executeWithAgent(pipeline: Pipeline, config: Config, paths: List<Path>) :PipelineResult {
    val agent = pipeline.agent
    val logger = pipeline.logger

    if(agent is DockerAgent) {
        logger.info("Docker image: ${agent.image}")
        logger.info("Docker tag: ${agent.tag}")
       return executeInDockerAgent(agent, config, paths)


    } else if (agent is KubernetesAgent) {
        logger.info("Kubernetes yalm: ${agent.yaml}")
        logger.info("Kubernetes label: ${agent.label}")
//        return executeInKubernetesAgent(agent, config, paths)

    }
    return PipelineResult(Status.Success, emptyList(), EnvVars(), mutableListOf())
}

fun executeInDockerAgent(agent: DockerAgent, config: Config, paths: List<Path>): PipelineResult {
    val containerManager = ContainerManager(agent, config, PipelineLogger(LogLevel.TRACE))

    containerManager.buildCustomImage("openjdk:17", paths)
    containerManager.createAndStartContainer(mapOf("IS_AGENT" to "true"))
    return PipelineResult(Status.Success, emptyList(), EnvVars(), mutableListOf())
}

fun readConfigFile(configFilePath: String): Config {
    val mapper = ObjectMapper(YAMLFactory())
    mapper.findAndRegisterModules()

    val configFile = File(configFilePath)
    if (!configFile.exists()) throw Exception("Config file not found")

    return mapper.readValue(configFile)
}

fun normalizeAndAbsolutePath(file: String): Path {
    return Path.of(file).toAbsolutePath().normalize()
}

fun normalizeAndAbsolutePath(path: Path): Path {
    return path.toAbsolutePath().normalize()
}

