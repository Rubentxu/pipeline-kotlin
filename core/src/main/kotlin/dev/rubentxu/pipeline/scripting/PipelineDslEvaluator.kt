package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.utils.normalizeAndAbsolutePath
import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

/**
 * Script evaluator for the Pipeline DSL that produces PipelineDefinition objects.
 */
class PipelineDslEvaluator : ScriptEvaluator<PipelineDefinition> {
    
    override val dslType: String = "pipeline"
    
    override fun evaluate(scriptPath: String): PipelineDefinition {
        val engine = getScriptEngine()
        val scriptFile = normalizeAndAbsolutePath(scriptPath).toFile()
        
        if (!scriptFile.exists()) {
            throw IllegalArgumentException("Script file $scriptPath does not exist")
        }
        
        val result = engine.eval(scriptFile.reader()) as? PipelineDefinition
        if (result != null) {
            return result
        }
        throw IllegalArgumentException("Script does not contain a PipelineDefinition")
    }
    
    private fun getScriptEngine(): ScriptEngine =
        ScriptEngineManager().getEngineByExtension("kts")
            ?: throw IllegalStateException("Script engine for .kts files not found")
}