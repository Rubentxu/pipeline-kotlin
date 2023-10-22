package dev.rubentxu.pipeline.dsl

class StagesDsl {
    val stages = mutableListOf<Stage>()

    fun stage(name: String, block: suspend StageDsl.() -> Unit) {
        stages.add(Stage(name, block))
    }
}