package dev.rubentxu.pipeline.model.pipeline

import dev.rubentxu.pipeline.dsl.PipelineBlock
import dev.rubentxu.pipeline.model.config.IPipelineConfig

/**
 * Represents the definition of a pipeline using a DSL block.
 *
 * @property block The DSL block that defines the pipeline structure and stages.
 */
class PipelineDefinition(
    /**
     * The DSL block that configures the pipeline.
     */
    val block: PipelineBlock.() -> Unit
) {
    /**
     * Builds a [Pipeline] instance using the provided configuration.
     *
     * @param configuration The pipeline configuration.
     * @return The built [Pipeline] object.
     */
    fun build(configuration: IPipelineConfig): Pipeline {
        return PipelineBlock().apply(block).build(configuration)
    }
}