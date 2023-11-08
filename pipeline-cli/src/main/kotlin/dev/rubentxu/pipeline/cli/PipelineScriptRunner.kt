package dev.rubentxu.pipeline.cli

import dev.rubentxu.pipeline.dsl.*
import dev.rubentxu.pipeline.logger.LogLevel
import dev.rubentxu.pipeline.logger.PipelineLogger
import kotlinx.coroutines.*
import java.io.File



fun evalWithScriptEngineManager(scriptFile: File): Any? {
    val logger = PipelineLogger(LogLevel.TRACE)
    val engine = javax.script.ScriptEngineManager().getEngineByExtension("kts")!!
    try {

        val pipelineDef = engine.eval(scriptFile.reader())
        if (pipelineDef is PipelineDefinition) {
            val pipeline = runBlocking {
                return@runBlocking pipelineDef.build(logger)
            }
            initializeAgent(pipeline, logger)
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

fun initializeAgent(pipeline: Pipeline, logger: PipelineLogger) {
    val agent = pipeline.agent

    if(agent is DockerAgent) {
        logger.info("Docker image: ${agent.image}")
        logger.info("Docker tag: ${agent.tag}")

    } else if (agent is KubernetesAgent) {
        logger.info("Kubernetes yalm: ${agent.yaml}")
        logger.info("Kubernetes label: ${agent.label}")
    }
}
