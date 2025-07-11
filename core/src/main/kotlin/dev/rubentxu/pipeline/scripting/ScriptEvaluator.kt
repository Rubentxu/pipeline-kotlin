package dev.rubentxu.pipeline.scripting

/**
 * Generic interface for script evaluation that can handle different DSL types.
 * 
 * @param T The type of the result produced by evaluating the script
 */
interface ScriptEvaluator<T> {
    /**
     * Evaluates a script file and returns the result of type T.
     * 
     * @param scriptPath The path to the script file to evaluate
     * @return The result of evaluating the script
     * @throws IllegalArgumentException if the script file doesn't exist or is invalid
     */
    fun evaluate(scriptPath: String): T
    
    /**
     * Gets the DSL type name that this evaluator handles.
     */
    val dslType: String
}