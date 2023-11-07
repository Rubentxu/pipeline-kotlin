package dev.rubentxu.pipeline.cli

import dev.rubentxu.pipeline.dsl.PipelineDefinition
import dev.rubentxu.pipeline.dsl.PipelineExecutor
import java.io.File

fun evalWithScriptEngineManager(scriptFile: File): Any? {
    val engine = javax.script.ScriptEngineManager().getEngineByExtension("kts")!!
    val pipelineDef = engine.eval(scriptFile.reader())
    if (pipelineDef is PipelineDefinition) {
        return PipelineExecutor().execute(pipelineDef)
    }
    return pipelineDef
}