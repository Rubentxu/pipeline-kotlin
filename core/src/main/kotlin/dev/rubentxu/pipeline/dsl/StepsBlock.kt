package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.logger.IPipelineLogger
import kotlinx.coroutines.*

/**
 * StepBlock represents a block of steps in a pipeline.
 * It implements Configurable and CoroutineScope interfaces.
 *
 * @property pipeline The pipeline in which this block of steps is being executed.
 */
@PipelineDsl
open class StepsBlock(val pipeline: Pipeline) :  CoroutineScope by CoroutineScope(Dispatchers.Default) {
    val logger: IPipelineLogger = pipeline.logger
    val env = pipeline.env

    val steps = mutableListOf<Step>()

    fun step(block: suspend () -> Any) {
        steps += Step(block)
    }

    fun parallel(vararg steps: Pair<String, Step>) = runBlocking {
        steps.map { (name, step) ->
            async {
                logger.info("Starting $name")
                step.block()
                logger.info("Finished $name")
            }
        }.awaitAll()
    }

}