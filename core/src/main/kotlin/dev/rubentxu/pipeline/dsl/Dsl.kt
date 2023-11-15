package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition


@DslMarker
annotation class PipelineDsl


/**
 * Executes a block of DSL code within a new Jenkins pipeline and returns the result.
 *
 * This function creates a new pipeline and runs a block of code in it.
 *  *
 *  * @param block A block of code to run in the pipeline.
 *  * @return A PipelineResult instance containing the results of the pipeline execution.
 *  */
fun pipeline(block: PipelineBlock.() -> Unit): Result<PipelineDefinition> {
    return try {
        val definition = PipelineDefinition(block)
        Result.success(definition) // Retorna un Result exitoso
    } catch (e: Exception) {
        Result.failure(e) // Retorna un Result con error
    }
}