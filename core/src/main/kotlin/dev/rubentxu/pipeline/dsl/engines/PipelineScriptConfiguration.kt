package dev.rubentxu.pipeline.dsl.engines

import dev.rubentxu.pipeline.dsl.PipelineBlock
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.resultField
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm


object PipelineScriptConfiguration : ScriptCompilationConfiguration({
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    implicitReceivers(PipelineBlock::class)
    defaultImports(
        "dev.rubentxu.pipeline.dsl.*",
        "dev.rubentxu.pipeline.model.pipeline.*"
    )
    // Configure script evaluation to return the last expression result
    resultField("definition")
})