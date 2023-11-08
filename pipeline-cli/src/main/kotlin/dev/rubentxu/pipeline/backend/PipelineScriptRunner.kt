package dev.rubentxu.pipeline.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import dev.rubentxu.pipeline.backend.agent.ContainerManager
import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.LogLevel
import dev.rubentxu.pipeline.logger.PipelineLogger
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Path


fun evalWithScriptEngineManager(scriptPath: Path, configPath: Path, executablePath: Path): Any? {
    val logger = PipelineLogger(LogLevel.TRACE)
    val engine = javax.script.ScriptEngineManager().getEngineByExtension("kts")!!
    try {
        val scriptFile = normalizeAndAbsolutePath(scriptPath).toFile()

        val pipelineDef = engine.eval(scriptFile.reader())
        if (pipelineDef is PipelineDefinition) {
            val pipeline = runBlocking {
                return@runBlocking pipelineDef.build(logger)
            }
            val listOfPaths = listOf(scriptPath, configPath, executablePath)
            val configuration = readConfigFile(normalizeAndAbsolutePath(configPath).toString())

            initializeAgent(pipeline, configuration, listOfPaths )
            return PipelineExecutor().execute(pipeline)
        }

        return pipelineDef
    } catch (e: Exception) {
        val regex = """ERROR (.*) expected \(ScriptingHost.*.kts:(\d+):(\d+)\)""".toRegex()

        val match = regex.find(e.message?:"")
        if (match == null) {
            logger.error("Error in Pipeline definition ::: ${e.message}")
        }
        val (error, line, space) = match!!.destructured

        logger.errorBanner(listOf("Error in Pipeline definition: $error","Line: $line","Space: $space"))
        return null
    }


}

fun initializeAgent(pipeline: Pipeline, config: Config, paths: List<Path>) {
    val agent = pipeline.agent
    val logger = pipeline.logger

    if(agent is DockerAgent) {
        logger.info("Docker image: ${agent.image}")
        logger.info("Docker tag: ${agent.tag}")
        createDockerAgent(agent, config, paths)

    } else if (agent is KubernetesAgent) {
        logger.info("Kubernetes yalm: ${agent.yaml}")
        logger.info("Kubernetes label: ${agent.label}")
    }
}

fun createDockerAgent(agent: DockerAgent, config: Config, paths: List<Path>) {
    val containerManager = ContainerManager(agent, config)

    containerManager.buildCustomImage("ubuntu:latest", paths)
    containerManager.createContainer(mapOf("MENSAJE" to "Hola Mundo"))
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

