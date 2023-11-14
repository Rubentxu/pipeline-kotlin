package dev.rubentxu.pipeline.model.pipeline


import dev.rubentxu.pipeline.dsl.PipelineBlock
import dev.rubentxu.pipeline.logger.PipelineLogger


class PipelineDefinition(val block: PipelineBlock.() -> Unit) {
    fun build(): Pipeline {
        return PipelineBlock().apply(block).build()
    }
}