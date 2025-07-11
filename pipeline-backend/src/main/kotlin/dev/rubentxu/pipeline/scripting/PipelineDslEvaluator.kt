package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition
import java.nio.file.Path
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

class PipelineDslEvaluator : ScriptEvaluator<PipelineDefinition> {
    
    override fun evaluate(scriptPath: Path): Result<PipelineDefinition> {
        return try {
            val engine = getScriptEngine()
            val scriptFile = scriptPath.toFile()
            if (!scriptFile.exists()) {
                return Result.failure(IllegalArgumentException("Script file ${scriptPath} does not exist"))
            }
            
            val result = engine.eval(scriptFile.reader()) as? PipelineDefinition
            if (result != null) {
                Result.success(result)
            } else {
                Result.failure(IllegalArgumentException("Script does not contain a PipelineDefinition"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun getScriptEngine(): ScriptEngine =
        ScriptEngineManager().getEngineByExtension("kts")
            ?: throw IllegalStateException("Script engine for .kts files not found")
}