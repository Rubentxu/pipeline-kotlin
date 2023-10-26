package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.logger.IPipelineLogger
import kotlinx.coroutines.*

/**
 * StepBlock represents a block of steps in a pipeline.
 * It implements Configurable and CoroutineScope interfaces.
 *
 * @property pipeline The pipeline in which this block of steps is being executed.
 */
open class StepBlock(val pipeline: PipelineDsl) :  CoroutineScope by CoroutineScope(Dispatchers.Default) {
    val logger: IPipelineLogger = pipeline.logger

    val steps = mutableListOf<Step>()

    fun step(block: suspend () -> Unit) {
        steps += Step(block)
    }

    fun parallel(vararg steps: Pair<String, Step>) = runBlocking {
        steps.map { (name, step) ->
            async {
                println("Starting $name")
                step.block()
                println("Finished $name")
            }
        }.awaitAll()
    }
}