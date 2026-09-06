package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.scripting.ReturnStatus
import dev.rubentxu.pipeline.v2.scripting.ReturnStdout
import dev.rubentxu.pipeline.v2.scripting.ScriptCompilationResult
import dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedDynamicScopeId
import dev.rubentxu.pipeline.v2.scripting.ScriptEvaluationOutput
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade

/**
 * Generated code calls this façade with a source-derived [ScriptedCallSiteId].
 * The façade has no replay or orchestration logic; it builds a typed command
 * and delegates the durable decision to the operation runtime.
 */
internal class RuntimeScriptedStepFacade(
    private val scope: ScriptedScope,
) : ScriptedStepFacade {
    override suspend fun <T> scoped(
        scopeId: ScriptedDynamicScopeId,
        block: suspend ScriptedStepFacade.() -> T,
    ): T = scope.scoped(scopeId) {
        RuntimeScriptedStepFacade(this).block()
    }

    override suspend fun sh(
        callSite: ScriptedCallSiteId,
        script: String,
        encoding: String?,
        label: String?,
    ) {
        scope.invokeAt(callSite, ShellCommand(script, encoding, label, ShellReturnMode.NONE)).asUnit()
    }

    override suspend fun sh(
        callSite: ScriptedCallSiteId,
        script: String,
        returnStdout: ReturnStdout,
        encoding: String?,
        label: String?,
    ): String = scope.invokeAt(callSite, ShellCommand(script, encoding, label, ShellReturnMode.STDOUT)).asStdout()

    override suspend fun sh(
        callSite: ScriptedCallSiteId,
        script: String,
        returnStatus: ReturnStatus,
        encoding: String?,
        label: String?,
    ): Int = scope.invokeAt(callSite, ShellCommand(script, encoding, label, ShellReturnMode.STATUS)).asStatus()
}

/** Closed result of selecting a host compilation for durable scripted execution. */
sealed interface ScriptedArtifactExecution {
    data class Executed(val entryPointId: String) : ScriptedArtifactExecution

    sealed interface Rejected : ScriptedArtifactExecution {
        data class CompilationFailed(
            val diagnostics: List<dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic>,
        ) : Rejected

        data object ReturnedValue : Rejected
        data object ReturnedNull : Rejected
        data object UnitOutput : Rejected
        data object NoOutput : Rejected
    }
}

/** Executes one compiled scripted entry point without serializing its continuation. */
class ScriptedArtifactRuntime(
    private val operationRuntime: ScriptedOperationRuntime,
) {
    suspend fun execute(runId: String, entryPoint: CompiledScriptedEntryPoint) {
        require(runId.isNotBlank()) { "Scripted run id must not be blank" }
        ScriptedRuntime(
            operationRuntime = operationRuntime,
            callSites = ScriptedCallSiteProvider {
                error("Compiled scripted entry points must supply explicit call-site ids")
            },
        ).run(
            definitionDigest = entryPoint.artifact.fingerprintMaterial(),
            entryPointId = entryPoint.entryPointId,
            runId = runId,
        ) {
            entryPoint.execute(RuntimeScriptedStepFacade(this))
        }
    }

    /**
     * Selects only a typed compiled entry point from the host result. Legacy
     * script values and compile failures are explicit non-effectful outcomes.
     */
    suspend fun execute(
        runId: String,
        compilation: ScriptCompilationResult,
    ): ScriptedArtifactExecution = when (compilation) {
        is ScriptCompilationResult.Failure -> ScriptedArtifactExecution.Rejected.CompilationFailed(
            compilation.diagnostics,
        )
        is ScriptCompilationResult.Success -> when (val output = compilation.output) {
            is ScriptEvaluationOutput.CompiledEntryPoint -> {
                execute(runId, output.entryPoint)
                ScriptedArtifactExecution.Executed(output.entryPoint.entryPointId)
            }
            is ScriptEvaluationOutput.ReturnedValue -> ScriptedArtifactExecution.Rejected.ReturnedValue
            ScriptEvaluationOutput.ReturnedNull -> ScriptedArtifactExecution.Rejected.ReturnedNull
            ScriptEvaluationOutput.Unit -> ScriptedArtifactExecution.Rejected.UnitOutput
            ScriptEvaluationOutput.NoValue -> ScriptedArtifactExecution.Rejected.NoOutput
        }
    }
}
