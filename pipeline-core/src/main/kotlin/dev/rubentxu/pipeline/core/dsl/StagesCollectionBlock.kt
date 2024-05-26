package dev.rubentxu.pipeline.core.dsl



/**
 * This class defines the DSL for creating a list of stages.
 */
@PipelineDsl
class StagesCollectionBlock : ArrayList<StageExecutor>() {

    /**
     * This function adds a stage to the list of stages.
     *
     * @param name The name of the stage.
     * @param block A block of code to run in the stage.
     */
    fun stage(name: String, block: StageBlock.() -> Any) {
        add(StageExecutor(name, block))
    }

    fun build(): List<StageExecutor> {
        return this
    }
}