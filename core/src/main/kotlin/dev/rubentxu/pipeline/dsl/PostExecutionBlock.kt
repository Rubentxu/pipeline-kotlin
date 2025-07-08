package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.pipeline.PostExecution

/**
 * DSL block for configuring post-execution actions in a pipeline stage.
 *
 * Allows defining actions to be executed always, on success, or on failure.
 */
class PostExecutionBlock {

    /**
     * Lambda to be executed always after the stage, regardless of the result.
     */
    var alwaysFunc: (suspend StepsBlock.() -> Any)? = null

    /**
     * Lambda to be executed only if the stage succeeds.
     */
    var successFunc: (suspend StepsBlock.() -> Any)? = null

    /**
     * Lambda to be executed only if the stage fails.
     */
    var failureFunc: (suspend StepsBlock.() -> Any)? = null

    /**
     * Defines a block to be executed always after the stage.
     *
     * @param block The suspend lambda to execute.
     */
    fun always(block: suspend StepsBlock.() -> Any) {
        this.alwaysFunc = { block() }
    }

    /**
     * Defines a block to be executed only if the stage succeeds.
     *
     * @param block The suspend lambda to execute.
     */
    fun success(block: suspend StepsBlock.() -> Any) {
        this.successFunc = { block() }
    }

    /**
     * Defines a block to be executed only if the stage fails.
     *
     * @param block The suspend lambda to execute.
     */
    fun failure(block: suspend StepsBlock.() -> Any) {
        this.failureFunc = { block() }
    }

    /**
     * Builds a [PostExecution] instance with the configured actions.
     *
     * @return The [PostExecution] object.
     */
    fun build(): PostExecution {
        return PostExecution(
            alwaysFunc = alwaysFunc,
            successFunc = successFunc,
            failureFunc = failureFunc
        )
    }
}