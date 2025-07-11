package dev.rubentxu.pipeline.scripting

class DslEvaluatorRegistry<T> {
    private val evaluators = mutableMapOf<String, ScriptEvaluator<T>>()
    
    fun register(dslType: String, evaluator: ScriptEvaluator<T>) {
        evaluators[dslType] = evaluator
    }
    
    fun getEvaluator(dslType: String): ScriptEvaluator<T> {
        return evaluators[dslType] 
            ?: throw IllegalArgumentException("No evaluator registered for DSL type: $dslType")
    }
    
    fun getSupportedTypes(): Set<String> = evaluators.keys.toSet()
}