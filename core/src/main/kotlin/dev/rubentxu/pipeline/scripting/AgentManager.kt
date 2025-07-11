package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import java.nio.file.Path

/**
 * Generic interface for managing agent execution.
 * 
 * @param T The type of definition that contains agent information
 */
interface AgentManager<T> {
    /**
     * Determines if the definition requires agent execution.
     * 
     * @param definition The definition to check
     * @return true if agent execution is required, false otherwise
     */
    fun requiresAgent(definition: T): Boolean
    
    /**
     * Executes the definition using an agent.
     * 
     * @param definition The definition to execute
     * @param configuration The configuration to use
     * @param paths The list of paths needed for execution
     * @return The result of the agent execution
     */
    fun executeWithAgent(definition: T, configuration: Any, paths: List<Path>): PipelineResult
}