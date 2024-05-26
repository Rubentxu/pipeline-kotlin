package dev.rubentxu.pipeline.core.pipeline


import dev.rubentxu.pipeline.core.interfaces.IPipeline
import dev.rubentxu.pipeline.core.dsl.PipelineBlock
import dev.rubentxu.pipeline.core.interfaces.IPipelineContext


class PipelineDefinition(val block: PipelineBlock.() -> Unit) {
    fun build(context: IPipelineContext): IPipeline {
        return PipelineBlock().apply(block).build(context)
    }
}