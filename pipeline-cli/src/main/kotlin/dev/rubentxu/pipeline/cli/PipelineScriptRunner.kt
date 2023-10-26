package dev.rubentxu.pipeline.cli

import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost


internal interface PipelineScriptRunner {
    fun run(script: File): ResultWithDiagnostics<EvaluationResult>
}

internal class PipelineScriptHost : PipelineScriptRunner {


    override fun run(script: File): ResultWithDiagnostics<EvaluationResult> {
        return BasicJvmScriptingHost().evalWithTemplate<PipelineScript>(
            script = script.toScriptSource(),
            evaluation = {
                implicitReceivers("dev.rubentxu.pipeline.dsl.*")
            }
        )
    }

}