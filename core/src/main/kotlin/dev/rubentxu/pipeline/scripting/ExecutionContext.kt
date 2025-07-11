package dev.rubentxu.pipeline.scripting

/**
 * Data class representing the execution context for script evaluation and execution.
 * 
 * @property environment A map of environment variables available during execution
 * @property dslType The type of DSL being executed (e.g., "pipeline", "task")
 */
data class ExecutionContext(
    val environment: Map<String, String>,
    val dslType: String
)