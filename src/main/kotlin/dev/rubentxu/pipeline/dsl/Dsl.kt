package dev.rubentxu.pipeline.dsl

import kotlinx.coroutines.runBlocking

object Dsl {

    fun pipeline(block: suspend PipelineDsl.() -> Unit) {
        val dsl = PipelineDsl()
        runBlocking {
            dsl.block()
        }
    }
}