package dev.rubentxu.pipeline.dsl

class Stage(val name: String, val block: suspend StageDsl.() -> Unit) {

    suspend fun run(pipeline: PipelineDsl) {
        println("==> Running '$name' stage...")
        val dsl = StageDsl(pipeline)
        dsl.block()
    }
}