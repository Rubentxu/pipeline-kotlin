package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.CoreFileExistsInput
import dev.rubentxu.pipeline.v2.application.CoreFileExistsStep
import dev.rubentxu.pipeline.v2.application.CoreIsUnixStep
import dev.rubentxu.pipeline.v2.application.CorePwdStep
import dev.rubentxu.pipeline.v2.application.CorePwdTmpStep
import dev.rubentxu.pipeline.v2.application.CoreReadFileInput
import dev.rubentxu.pipeline.v2.application.CoreReadFileStep
import dev.rubentxu.pipeline.v2.application.CoreShellInput
import dev.rubentxu.pipeline.v2.application.CoreShellStep
import dev.rubentxu.pipeline.v2.application.IsUnixInput
import dev.rubentxu.pipeline.v2.application.PwdInput
import dev.rubentxu.pipeline.v2.application.PwdTmpInput
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
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
    private val registryInvoker: ScriptedRegistryInvoker? = null,
) : ScriptedStepFacade {

    /**
     * S4-A1 — the façade's single seam to durable execution.
     *
     * Everything a runtime-returning call needs before it can name a Step is
     * here, once: the invoker must exist (fail loud, never a fabricated value),
     * the scope identity, the call-site ordinal, the Step's declared input and
     * output codecs, and the typed-failure translation.
     *
     * A façade body is therefore reduced to the only part that is genuinely
     * Step-specific — which definition, which input, and which projection of
     * the typed output. Six near-identical thirty-line bodies collapse to six
     * three-line ones, and the part that decides durable semantics lives in one
     * place instead of six that could drift.
     */
    private suspend fun <I : Any, O : Any> call(
        callSite: ScriptedCallSiteId,
        definition: dev.rubentxu.pipeline.v2.domain.step.StepDefinition<I, O>,
        input: I,
    ): O {
        val invoker = registryInvoker
            ?: throw dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation(
                "runtime-returning scripted steps require a registry invoker; " +
                    "wire ScriptedRegistryInvoker into this entry point runtime",
            )
        return invoker.invokeTyped(
            identity = scope.identity,
            callSiteId = callSite,
            invocationOrdinal = scope.nextOrdinal(callSite),
            definition = definition,
            input = input,
        )
    }

    override suspend fun <T> scoped(
        scopeId: ScriptedDynamicScopeId,
        block: suspend ScriptedStepFacade.() -> T,
    ): T = scope.scoped(scopeId) {
        RuntimeScriptedStepFacade(this, registryInvoker).block()
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

    /**
     * LFC-2R / R2 — the first runtime-returning scripted consumer of the generic
     * scripted→registry seam. This is a THIN ADAPTATION: identity + encoded unit
     * input → [ScriptedRegistryInvoker] → encoded output → the Step's OWN
     * [dev.rubentxu.pipeline.v2.domain.step.StepCodec] → Boolean.
     *
     * Classification authority stays exclusively inside the registry Step: this
     * façade must never read the platform, classify, or hand-decode the payload
     * (architecture fitness pins the absence of those identifiers).
     *
     * The nullable [registryInvoker] keeps R1's shell-only entry points compiling;
     * when it is absent, the runtime-returning surface is unavailable (fail loud,
     * never a fabricated value).
     */
    override suspend fun isUnix(callSite: ScriptedCallSiteId): Boolean =
        call(callSite, CoreIsUnixStep.definition, IsUnixInput).isUnix

    /**
     * WU-LPR-402 — runtime-returning workspace query façade. Mirrors [isUnix]
     * in shape: identity + encoded input → [ScriptedRegistryInvoker] →
     * encoded output → the Step's OWN [StepCodec] → String path.
     *
     * Two durable StepKeys, decided by [tmp]:
     *   - `core.pwd`     — `CorePwdStep` (registry, READ_ONLY + MEMOIZED)
     *   - `core.pwd.tmp` — `CorePwdTmpStep` (registry, deterministic tmp dir)
     *
     * The StepKey routing is owned here, NOT inside the registry or the codec.
     * The mapper emits a single `ScriptedCallKind.Pwd(tmp)`; the façade picks
     * the registry authority. Replay never re-observes; it reproduces the
     * persisted `PwdOutput.path`.
     */
    override suspend fun pwd(callSite: ScriptedCallSiteId, tmp: Boolean): String =
        if (tmp) {
            call(callSite, CorePwdTmpStep.definition, PwdTmpInput).path
        } else {
            call(callSite, CorePwdStep.definition, PwdInput(tmp = false)).path
        }

    /**
     * WU-LPR-087 (LFC-2R2) — runtime-returning file-read façade. Mirrors the
     * existing `pwd` impl shape: identity + encoded input → invoker → encoded
     * output → Step's declared codec → String.
     *
     * The StepKey is `core.readFile`, registered in
     * [dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory] alongside
     * `core.pwd`. Missing registry → typed Rejection, never a fabricated empty
     * String.
     */
    override suspend fun readFile(
        callSite: ScriptedCallSiteId,
        file: String,
    ): String {
        val output = call(
            callSite,
            CoreReadFileStep.definition,
            CoreReadFileInput(file = file, encoding = "UTF-8"),
        )
        if (!output.exists) {
            throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
                    "readFile($file) reported exists=false; runtime-returning façade cannot materialise content",
                ),
            )
        }
        return output.content
            ?: throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
                    "persisted readFile output has exists=true but content=null",
                ),
            )
    }

    /**
     * WU-LPR-087 (LFC-2R2) — runtime-returning file-existence check façade.
     * Mirrors the existing `isUnix` impl shape (returns Boolean): identity +
     * encoded input → invoker → encoded output → Step's declared codec →
     * Boolean. The StepKey is `core.fileExists`.
     */
    override suspend fun fileExists(
        callSite: ScriptedCallSiteId,
        file: String,
    ): Boolean =
        call(callSite, CoreFileExistsStep.definition, CoreFileExistsInput(file = file)).exists

    /**
     * WU-LPR-087 (LFC-2R2) — runtime-returning `sh(..., returnStdout = true)`
     * façade. Mirrors the existing `pwd` impl shape but routes through
     * [CoreShellStep] with [ShellReturnMode.STDOUT]: identity + encoded input
     * → invoker → encoded output → Step's declared codec → captured stdout
     * String.
     *
     * Reuses the certified `core.sh` Step; no new StepKey, no new capability.
     * The rewriter produces `steps.shReturnStdout(callSite, script)` calls
     * with the script text preserved; the façade encodes the same script with
     * `returnMode = STDOUT` and decodes `CoreShellOutput.stdout`.
     */
    override suspend fun shReturnStdout(
        callSite: ScriptedCallSiteId,
        script: String,
        encoding: String?,
    ): String {
        val output = call(
            callSite,
            CoreShellStep.definition,
            CoreShellInput(
                command = ShellCommand(
                    script = script,
                    encoding = encoding,
                    label = null,
                    returnMode = ShellReturnMode.STDOUT,
                ),
            ),
        )
        val stdoutValue = (output.result as? dev.rubentxu.pipeline.v2.domain.ShellInvocationResult.Stdout)?.value
            ?: throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                    dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
                    "core.sh succeeded but the persisted output is not a Stdout variant " +
                        "(got ${output.result::class.simpleName}); the sh(returnStdout=true) facade requires ShellReturnMode.STDOUT",
                ),
            )
        return stdoutValue
    }
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
    private val registryInvoker: ScriptedRegistryInvoker? = null,
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
            entryPoint.execute(RuntimeScriptedStepFacade(this, registryInvoker))
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
