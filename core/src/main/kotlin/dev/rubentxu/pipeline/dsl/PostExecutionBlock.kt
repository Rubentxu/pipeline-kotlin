package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.pipeline.PostExecution

class PostExecutionBlock {
    var alwaysFunc: suspend StepsBlock.() -> Any = { }
    var successFunc: suspend StepsBlock.() -> Any = { }
    var failureFunc: suspend StepsBlock.() -> Any = { }
    fun always(block: suspend StepsBlock.() -> Any) {
        this.alwaysFunc = { block() }
    }

    fun success(block: suspend StepsBlock.() -> Any) {
        this.successFunc = { block() }
    }

    fun failure(block: suspend StepsBlock.() -> Any) {
        this.failureFunc = { block() }
    }

    fun build(): PostExecution {
        return PostExecution(
            alwaysFunc = alwaysFunc,
            successFunc = successFunc,
            failureFunc = failureFunc
        )
    }
}