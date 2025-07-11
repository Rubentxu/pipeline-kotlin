package dev.rubentxu.pipeline.backend.execution

/**
 * Execution context for pipeline runs.
 *
 * @property environment Environment variables for the execution
 * @property isAgentEnvironment Whether this is running in an agent environment
 */
data class ExecutionContext(
    val environment: Map<String, String>,
    val isAgentEnvironment: Boolean = environment.containsKey("IS_AGENT")
)