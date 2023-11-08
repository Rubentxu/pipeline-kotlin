package dev.rubentxu.pipeline.dsl

class PostExecutionBlock(val pipeline: Pipeline) {
    var logger = pipeline.logger
    var alwaysFunc: suspend StepsBlock.() -> Any = { }
    var successFunc: suspend StepsBlock.() -> Any = { }
    var failureFunc: suspend StepsBlock.() -> Any = { }
    fun always(block: suspend StepsBlock.() -> Any) {
        logger.system("Registering always block")
        this.alwaysFunc = { block() }
    }

    fun success(block: suspend StepsBlock.() -> Any) {
        logger.system("Registering success block")
        this.successFunc = { block() }
    }

    fun failure(block: suspend StepsBlock.() -> Any) {
        logger.system("Registering failure block")
        this.failureFunc = { block() }
    }
}