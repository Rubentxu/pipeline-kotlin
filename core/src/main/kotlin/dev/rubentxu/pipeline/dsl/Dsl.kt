package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.pipeline.PipelineDefinition


@DslMarker
annotation class PipelineDsl


/**
 * Executes a block of DSL code within a new pipeline and returns the definition.
 *
 * This function creates a new pipeline definition using the provided DSL block.
 * Error handling is delegated to the DSL engine for consistent error reporting.
 *
 * @param block A block of code to define the pipeline structure.
 * @return A PipelineDefinition instance containing the pipeline configuration.
 */
fun pipeline(block: PipelineBlock.() -> Unit): PipelineDefinition {
    return PipelineDefinition(block)
}

