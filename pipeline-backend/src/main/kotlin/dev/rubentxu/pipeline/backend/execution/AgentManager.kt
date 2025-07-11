package dev.rubentxu.pipeline.backend.execution

import dev.rubentxu.pipeline.model.PipelineConfig
import dev.rubentxu.pipeline.model.pipeline.Agent
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import java.nio.file.Path

/**
 * Interface for managing pipeline execution on different agents.
 */
interface AgentManager {
    /**
     * Checks if this manager can handle the given agent type.
     *
     * @param agent The agent to check
     * @return true if this manager can handle the agent, false otherwise
     */
    fun canHandle(agent: Agent): Boolean

    /**
     * Executes a pipeline on the agent with the provided files.
     *
     * @param pipeline The pipeline to execute
     * @param config The pipeline configuration
     * @param files List of files to include in the execution context
     * @return Result containing the pipeline result or an error
     */
    fun execute(pipeline: Pipeline, config: PipelineConfig, files: List<Path>): Result<PipelineResult>
}