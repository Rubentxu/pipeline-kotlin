package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.steps.EnvVars

/**
 * Enum representing the status of a pipeline or stage.
 */
enum class Status {
    Success,
    Failure,
    Unstable,
    Aborted,
    NotBuilt
}

/**
 * Data class representing the result of a pipeline stage.
 *
 * @property name The name of the stage.
 * @property status The status of the stage (success or failure).
 * @property output The output from the steps in the stage.
 */
data class StageResult(
    val name: String,
    val status: Status

)

/**
 * Data class representing the result of a Jenkins pipeline execution.
 *
 * @property status The overall status of the pipeline (success or failure).
 * @property stageResults The results of each individual stage in the pipeline.
 * @property env The state of the environment variables at the end of the pipeline.
 */
data class PipelineResult(
    val status: Status,
    val stageResults: List<StageResult>,
    val env: EnvVars,
    val logs: MutableList<String>
)

/**
 * Executes a block of DSL code within a new Jenkins pipeline and returns the result.
 *
 * This function creates a new pipeline and runs a block of code in it.
 *  *
 *  * @param block A block of code to run in the pipeline.
 *  * @return A PipelineResult instance containing the results of the pipeline execution.
 *  */
suspend fun pipeline(block: suspend PipelineDsl.() -> Unit): PipelineResult {
    val pipeline = PipelineDsl()

    var status: Status

    try {
        pipeline.block()

        status = if (pipeline.stageResults.any { it.status == Status.Failure }) Status.Failure else Status.Success
    } catch (e: Exception) {
        status = Status.Failure
        pipeline.stageResults.addAll(listOf(StageResult("Unknown", status)))
        throw e
    }

    return PipelineResult(status, pipeline.stageResults, pipeline.env, pipeline.logger.logs)
}

