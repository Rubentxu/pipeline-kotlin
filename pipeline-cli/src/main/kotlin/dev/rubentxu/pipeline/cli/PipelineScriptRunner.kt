package dev.rubentxu.pipeline.cli

import dev.rubentxu.pipeline.dsl.PipelineDefinition
import dev.rubentxu.pipeline.dsl.PipelineExecutor
import dev.rubentxu.pipeline.logger.LogLevel
import dev.rubentxu.pipeline.logger.PipelineLogger
import java.io.File

fun evalWithScriptEngineManager(scriptFile: File): Any? {
    val logger = PipelineLogger(LogLevel.TRACE)
    val engine = javax.script.ScriptEngineManager().getEngineByExtension("kts")!!
    try {

        val pipelineDef = engine.eval(scriptFile.reader())
        if (pipelineDef is PipelineDefinition) {
            return PipelineExecutor(logger).execute(pipelineDef)
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