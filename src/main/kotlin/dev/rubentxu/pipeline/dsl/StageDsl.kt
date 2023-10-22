package dev.rubentxu.pipeline.dsl


class StageDsl(val pipeline: PipelineDsl) {

    suspend fun steps(block: suspend Steps.() -> Unit) {
        val steps = Steps(pipeline)
        steps.block()
    }
}