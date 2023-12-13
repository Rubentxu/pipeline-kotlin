package dev.rubentxu.pipeline.model.pipeline

import dev.rubentxu.pipeline.steps.EnvVars

/**
 * Data class representing the result of a Jenkins pipeline execution.
 *
 * @property status The overall status of the pipeline (success or failure).
 * @property stageResults The results of each individual stage in the pipeline.
 * @property env The state of the environment variables at the end of the pipeline.
 */
data class JobResult(
    val status: Status,
    val stageResults: List<StageResult>,
    val env: EnvVars,
    val logs: List<String>
)