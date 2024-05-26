package dev.rubentxu.pipeline.core.dsl

import dev.rubentxu.pipeline.core.interfaces.ILogger
import dev.rubentxu.pipeline.core.interfaces.IPipelineContext
import kotlinx.coroutines.*

@PipelineDsl
open class StepsBlock(val context: IPipelineContext) {
    lateinit var logger: ILogger

    val steps = mutableListOf<Step>()

    fun step(block: suspend () -> Any) {
        steps += Step(block)
    }

    fun parallel(vararg steps: Pair<String, Step>) = runBlocking {
        steps.map { (name, step) ->
            async {
                logger.info("Parallel $name", "Starting $name")
                step.block()
                logger.info("Parallel $name","Finished $name")
            }
        }.awaitAll()
    }

    suspend fun init() {
        logger = context.getComponent(ILogger::class)
    }
}