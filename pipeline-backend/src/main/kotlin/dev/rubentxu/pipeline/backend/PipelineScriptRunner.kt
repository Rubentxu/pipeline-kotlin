package dev.rubentxu.pipeline.backend

import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.backend.ktor.configureRouting
import dev.rubentxu.pipeline.backend.ktor.configureSerialization
import dev.rubentxu.pipeline.model.pipeline.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import org.koin.dsl.module
import java.util.*


//
//class PipelineScriptRunner {
//
//    companion object {
//        @JvmStatic
//        fun evalWithScriptEngineManager(
//            scriptPath: String,
//            configPath: String,
//            jarLocation: File = File(PipelineScriptRunner::class.java.protectionDomain.codeSource.location.toURI()),
//            logger: PipelineLogger = PipelineLogger(logLevel = LogLevel.TRACE, logConfigurationStrategy = SocketLogConfigurationStrategy())
//        ): JobResult {
//
//            val pipelineExecutable = Path.of("", "pipeline-kts").toAbsolutePath().toFile()
//            logger.info("Pipeline executable: ${pipelineExecutable.absolutePath}")
//            logger.info("Pipeline executable exists: ${pipelineExecutable.exists()}")
//
//            logger.info("JAR location: ${jarLocation.absolutePath}")
//            val resolveExecutablePath =
//                if (pipelineExecutable.exists()) pipelineExecutable.absolutePath else jarLocation.absolutePath
//            logger.info("Resolve executable path: $resolveExecutablePath")
//
//            return try {
//                val pipelineDef = evaluateScriptFile(scriptPath)
//                logger.system("Pipeline definition: $pipelineDef")
//                val pipeline = buildPipeline(pipelineDef)
//                logger.system("Build Pipeline: $pipeline")
//                executePipeline(pipeline, scriptPath, configPath, resolveExecutablePath, logger)
//
//            } catch (e: Exception) {
//                handleScriptExecutionException(e)
//                JobResult(Status.Failure, emptyList(), EnvVars(mapOf()), mutableListOf())
//            }
//        }
//    }
//}
//
//
//
//
//
//
//// Procesa la definición del pipeline después de la evaluación.
//suspend fun executePipeline(
//    pipeline: Pipeline,
//    scriptPath: String,
//    configPath: String,
//    executablePath: String,
//    logger: PipelineLogger
//): JobResult {
//
//    val listOfPaths = listOf(scriptPath, configPath, executablePath).map { normalizeAndAbsolutePath(it) }
////    val configuration = readConfigFile(normalizeAndAbsolutePath(configPath).toString())
//    val configurationResult = CascManager().resolveConfig(normalizeAndAbsolutePath(configPath))
//
//    if (configurationResult.isFailure) {
//        logger.error("Error reading config file: ${configurationResult.exceptionOrNull()?.message}")
//        return JobResult(Status.Failure, emptyList(), EnvVars(mapOf()), mutableListOf())
//    }
//    val configuration = configurationResult.getOrThrow()
//
//    val isAgentEnv = System.getenv("IS_AGENT")
//    logger.system("Env isAgent: $isAgentEnv")
//    // si pipeline.agent no es AnyAgent se ejecuta en un agente
//    if (!(pipeline.agent is AnyAgent) && isAgentEnv == null) {
//        return executeWithAgent(pipeline, configuration, listOfPaths)
//    }
//
//    val jobInstance = JobInstance(pipeline, configuration)
//
//    val execution =  JobLauncherImpl().launch()
//    execution.job.join()
//    return execution.result
//}
//
//// Construye el pipeline usando coroutines.
//
//
//// Maneja las excepciones ocurridas durante la ejecución del script.
//fun handleScriptExecutionException(exception: Exception, showStackTrace: Boolean = true) {
//    val logger: PipelineLogger = PipelineLogger.getLogger() as PipelineLogger
//    val regex = """ERROR (.*) \(.*:(\d+):(\d+)\)""".toRegex()
//    val match = regex.find(exception.message ?: "")
//
//    if (match != null) {
//        val (error, line, space) = match.destructured
//        logger.errorBanner(listOf("Error in Pipeline definition: $error", "Line: $line", "Space: $space"))
//    } else {
//        logger.error("Error in Pipeline definition: ${exception.message}")
//    }
//
//    // Imprime el stacktrace completo si el flag está activado.
//    if (showStackTrace) {
//        exception.printStackTrace()
//    }
//}
//
//fun executeWithAgent(pipeline: Pipeline, config: PipelineContext, paths: List<Path>): JobResult {
//    val agent = pipeline.agent
//    val logger = PipelineLogger.getLogger()
//
//    if (agent is DockerAgent) {
//        logger.info("Docker image: ${agent.image}")
//        logger.info("Docker tag: ${agent.tag}")
//        return executeInDockerAgent(agent, config, paths)
//
//
//    } else if (agent is KubernetesAgent) {
//        logger.info("Kubernetes yalm: ${agent.yaml}")
//        logger.info("Kubernetes label: ${agent.label}")
////        return executeInKubernetesAgent(agent, config, paths)
//
//    }
//    return JobResult(Status.Failure, emptyList(), EnvVars(mapOf()), mutableListOf())
//}
//
//fun executeInDockerAgent(agent: DockerAgent, config: PipelineContext, paths: List<Path>): JobResult {
//    val dockerClientProvider = DockerConfigManager(agent)
//    val imageBuilder = DockerImageBuilder(dockerClientProvider)
//    val containerManager = ContainerLifecycleManager(dockerClientProvider)
//
//    val imageId = imageBuilder.buildCustomImage("${agent.image}:${agent.tag}", paths)
//    containerManager.createAndStartContainer(mapOf("IS_AGENT" to "true"))
//    return JobResult(Status.Success, emptyList(), EnvVars(mapOf()), mutableListOf())
//}
//
//

fun main() {
    embeddedServer(CIO, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun pluginsModule() = module {
    val plugins = loadServices<PipelineComponent>()

    plugins.forEach { plugin ->
        single { plugin }
    }
}

inline fun <reified T : Any> loadServices(): List<T> {
    return ServiceLoader.load(T::class.java).iterator().asSequence().toList()
}

fun Application.module() {

    configureSerialization()
    configureRouting()
}

