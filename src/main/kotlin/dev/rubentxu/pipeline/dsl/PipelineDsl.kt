package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.steps.EnvVars
import kotlinx.coroutines.*

class PipelineDsl {
    val any = Placeholder.ANY
    val env = EnvVars()

    // return a instance of PipelineDsl
    fun getPipeline(): PipelineDsl {
        return this
    }

    suspend fun agent(any: Placeholder) {
        println("Running pipeline using any available agent...")
    }

    suspend fun environment(block: EnvVars.() -> Unit) {
        env.apply(block)
    }

    suspend fun stages(block: StagesDsl.() -> Unit) {
        val dsl = StagesDsl()
        dsl.block()

        coroutineScope {
            dsl.stages.forEach { stage ->
                launch {
                    stage.run(getPipeline())
                }
            }
        }
    }

    enum class Placeholder {
        ANY
    }
}

suspend fun pipeline(block: suspend PipelineDsl.() -> Unit) {
    val pipeline = PipelineDsl()
    pipeline.block()
    // Aquí ejecutarías la pipeline
}




