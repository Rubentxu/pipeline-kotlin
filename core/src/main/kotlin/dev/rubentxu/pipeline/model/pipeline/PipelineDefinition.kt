package dev.rubentxu.pipeline.model.pipeline


import dev.rubentxu.pipeline.dsl.PipelineBlock
import dev.rubentxu.pipeline.model.config.IPipelineConfig


class PipelineDefinition(val block: PipelineBlock.() -> Unit) {
    fun build(configuration: IPipelineConfig): Pipeline {
        return PipelineBlock().apply(block).build(configuration)
    }
}