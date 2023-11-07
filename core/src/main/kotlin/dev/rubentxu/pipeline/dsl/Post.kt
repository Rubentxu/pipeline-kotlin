package dev.rubentxu.pipeline.dsl

class Post(val pipeline: Pipeline) {
    var logger = pipeline.logger
    var alwaysFunc: suspend StepBlock.() -> Any = { }
    var successFunc: suspend StepBlock.() -> Any = { }
    var failureFunc: suspend StepBlock.() -> Any = { }
    fun always(block: suspend StepBlock.() -> Any) {
        logger.system("Registering always block")
        this.alwaysFunc = { block() }
    }

    fun success(block: suspend StepBlock.() -> Any) {
        logger.system("Registering success block")
        this.successFunc = { block() }
    }

    fun failure(block: suspend StepBlock.() -> Any) {
        logger.system("Registering failure block")
        this.failureFunc = { block() }
    }
}