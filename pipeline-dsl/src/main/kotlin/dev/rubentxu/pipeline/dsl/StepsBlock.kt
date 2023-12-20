package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.IPipelineContext
import dev.rubentxu.pipeline.model.logger.IPipelineLogger
import kotlinx.coroutines.*

@PipelineDsl
open class StepsBlock(val context: IPipelineContext) {
    lateinit var logger: IPipelineLogger

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

    suspend fun init() {
        logger = context.getService(IPipelineLogger::class) as IPipelineLogger
    }
}