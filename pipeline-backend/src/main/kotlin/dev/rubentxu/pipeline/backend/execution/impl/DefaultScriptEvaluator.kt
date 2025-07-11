package dev.rubentxu.pipeline.backend.execution.impl

import dev.rubentxu.pipeline.backend.execution.ScriptEvaluator
import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition
import java.io.FileNotFoundException
import java.nio.file.Path
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

/**
 * Default implementation of ScriptEvaluator using Kotlin script engine.
 */
class DefaultScriptEvaluator : ScriptEvaluator {
    
    override fun evaluate(scriptPath: Path): Result<PipelineDefinition> {
        return try {
            val scriptFile = scriptPath.normalize().toFile()
            
            if (!scriptFile.exists()) {
                return Result.failure(FileNotFoundException("Script file ${scriptPath} does not exist"))
            }
            
            val engine = getScriptEngine()
                ?: return Result.failure(IllegalStateException("Script engine for .kts files not found"))
            
            scriptFile.reader().use { reader ->
                val result = engine.eval(reader) as? PipelineDefinition
                    ?: return Result.failure(IllegalArgumentException("Script does not contain a PipelineDefinition"))
                
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun getScriptEngine(): ScriptEngine? {
        return ScriptEngineManager().getEngineByExtension("kts")
    }
}