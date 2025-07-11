package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.backend.normalizeAndAbsolutePath
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

/**
 * Script evaluator for the Task DSL that produces TaskDefinition objects.
 * This is a simple example evaluator for demonstration purposes.
 */
class TaskDslEvaluator : ScriptEvaluator<TaskDefinition> {
    
    override val dslType: String = "task"
    
    override fun evaluate(scriptPath: String): TaskDefinition {
        val engine = getScriptEngine()
        val scriptFile = normalizeAndAbsolutePath(scriptPath).toFile()
        
        if (!scriptFile.exists()) {
            throw IllegalArgumentException("Script file $scriptPath does not exist")
        }
        
        val result = engine.eval(scriptFile.reader()) as? TaskDefinition
        if (result != null) {
            return result
        }
        throw IllegalArgumentException("Script does not contain a TaskDefinition")
    }
    
    private fun getScriptEngine(): ScriptEngine =
        ScriptEngineManager().getEngineByExtension("kts")
            ?: throw IllegalStateException("Script engine for .kts files not found")
}