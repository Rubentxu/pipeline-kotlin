package dev.rubentxu.pipeline.core.jobs


/**
 * Data class representing the result of a pipeline stage.
 *
 * @property name The name of the stage.
 * @property status The status of the stage (success or failure).
 * @property output The output from the steps in the stage.
 */
data class StageResult(
    val name: String,
    val status: Status,
    val output: String = "",
    val error: String = ""

)