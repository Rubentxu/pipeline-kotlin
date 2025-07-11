package dev.rubentxu.pipeline.backend.execution

import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition
import java.nio.file.Path

/**
 * Interface for evaluating pipeline scripts.
 */
interface ScriptEvaluator {
    /**
     * Evaluates a script file and returns the pipeline definition.
     *
     * @param scriptPath The path to the script file
     * @return Result containing the pipeline definition or an error
     */
    fun evaluate(scriptPath: Path): Result<PipelineDefinition>
}