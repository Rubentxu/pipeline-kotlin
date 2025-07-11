package dev.rubentxu.pipeline.scripting

import dev.rubentxu.pipeline.model.pipeline.PipelineResult

/**
 * Generic interface for executing pipelines or tasks.
 * 
 * @param T The type of definition to execute (e.g., PipelineDefinition, TaskDefinition)
 */
interface PipelineExecutor<T> {
    /**
     * Executes the given definition with the provided configuration.
     * 
     * @param definition The pipeline or task definition to execute
     * @param configuration The configuration to use during execution
     * @param context The execution context
     * @return The result of the execution
     */
    fun execute(definition: T, configuration: Any, context: ExecutionContext): PipelineResult
}