package dev.rubentxu.pipeline.scripting

/**
 * Registry for managing DSL evaluators by type.
 * This allows for pluggable DSL support by registering different evaluators for different DSL types.
 */
class DslEvaluatorRegistry {
    private val evaluators = mutableMapOf<String, ScriptEvaluator<*>>()
    
    /**
     * Registers a script evaluator for a specific DSL type.
     * 
     * @param dslType The DSL type name
     * @param evaluator The script evaluator for this DSL type
     */
    fun <T> registerEvaluator(dslType: String, evaluator: ScriptEvaluator<T>) {
        evaluators[dslType] = evaluator
    }
    
    /**
     * Gets the script evaluator for a specific DSL type.
     * 
     * @param dslType The DSL type name
     * @return The script evaluator for the DSL type
     * @throws IllegalArgumentException if no evaluator is registered for the DSL type
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getEvaluator(dslType: String): ScriptEvaluator<T> {
        return evaluators[dslType] as? ScriptEvaluator<T> 
            ?: throw IllegalArgumentException("No evaluator registered for DSL type: $dslType")
    }
    
    /**
     * Gets all registered DSL types.
     * 
     * @return A set of all registered DSL type names
     */
    fun getRegisteredDslTypes(): Set<String> {
        return evaluators.keys.toSet()
    }
}