package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import kotlinx.coroutines.*

/**
 * StepBlock represents a block of steps in a pipeline.
 * It implements Configurable and CoroutineScope interfaces.
 *
 * @property pipeline The pipeline in which this block of steps is being executed.
 */
@PipelineDsl
open class StepsBlock(val pipeline: Pipeline) {
    val logger: IPipelineLogger = PipelineLogger.getLogger()
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