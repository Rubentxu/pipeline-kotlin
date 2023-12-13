package dev.rubentxu.pipeline.dsl

interface NodeDsl {
    val any: Placeholder

//    fun node(closure: dev.rubentxu.pipeline.dsl.StagesDsl.() -> Unit) {
//        val environmentVariables = Job.script.getBinding().getVariables()?.environment
//        this.env.putAll(environmentVariables.orEmpty())
//        this.credentials.addAll(Job.script.getBinding().getVariables()?.credentials.orEmpty())
//        this.configureScm(Job.script.getBinding().getVariables()?.scmConfig)
//
//        val dsl = dev.rubentxu.pipeline.dsl.StagesDsl()
//        dsl.closure()
//
//        dsl.stages.forEach { stage ->
//            stage.run()
//        }
//    }

    enum class Placeholder {
        ANY
    }
}
