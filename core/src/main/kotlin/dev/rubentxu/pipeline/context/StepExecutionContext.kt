package dev.rubentxu.pipeline.context

import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.logger.IPipelineLogger

/**
 * Context for step execution that provides controlled access to pipeline resources.
 * This context ensures steps can only access authorized resources and cannot be used outside the DSL.
 */
@JvmInline
value class StepExecutionContext private constructor(
    private val executionData: ExecutionData
) {
    val pipeline: Pipeline
        get() = executionData.pipeline
    
    val logger: IPipelineLogger
        get() = executionData.logger
    
    val workingDirectory: String
        get() = executionData.workingDirectory
    
    val environment: Map<String, String>
        get() = executionData.environment
    
    companion object {
        /**
         * Creates a new step execution context. This should only be called by the pipeline execution engine.
         */
        internal fun create(
            pipeline: Pipeline,
            logger: IPipelineLogger,
            workingDirectory: String = pipeline.workingDir.toString(),
            environment: Map<String, String> = pipeline.env
        ): StepExecutionContext {
            return StepExecutionContext(
                ExecutionData(
                    pipeline = pipeline,
                    logger = logger,
                    workingDirectory = workingDirectory,
                    environment = environment
                )
            )
        }
    }
    
    private data class ExecutionData(
        val pipeline: Pipeline,
        val logger: IPipelineLogger,
        val workingDirectory: String,
        val environment: Map<String, String>
    )
}

/**
 * Marker interface for step execution scope.
 * Only objects implementing this interface can provide step execution capabilities.
 */
interface StepExecutionScope {
    val stepContext: StepExecutionContext
}