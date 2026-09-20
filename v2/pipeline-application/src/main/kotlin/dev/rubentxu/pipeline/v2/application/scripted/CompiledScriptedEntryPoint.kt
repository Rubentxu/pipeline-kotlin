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
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
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
    override suspend fun isUnix(callSite: ScriptedCallSiteId): Boolean {
        val invoker = registryInvoker
            ?: throw dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation(
                "runtime-returning scripted steps require a registry invoker; " +
                    "wire ScriptedRegistryInvoker into this entry point runtime",
            )
        val identity = scope.identity
        val result = invoker.invoke(
            ScriptedRegistryCall(
                runId = identity.runId,
                entryPointId = identity.entryPointId,
                callSiteId = callSite,
                dynamicScopePath = identity.dynamicScopePath,
                invocationOrdinal = scope.nextOrdinal(callSite),
                stepKey = CoreIsUnixStep.KEY,
                encodedInput = CoreIsUnixStep.definition.contract.inputCodec.encode(IsUnixInput),
            ),
        )
        return when (result) {
            is ScriptedRegistryResult.Success -> try {
                decodeRuntimeBoolean(result.encodedOutput)
            } catch (e: IllegalArgumentException) {
                throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                    dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
                        "persisted runtime output is not decodable by the step's declared codec: ${e.message}",
                    ),
                )
            }
            is ScriptedRegistryResult.Failed -> throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                result.failure,
            )
        }
    }

    /**
     * Typed projection through the Step's DECLARED output codec — never a
     * hand-written parallel decoder. The codec is static Step-owned code, not a
     * runtime capability, so the REUSE path (empty registry, zero capabilities)
     * can still decode the persisted payload with the SAME codec that encoded
     * it fresh: one output contract, both directions.
     */
    private fun decodeRuntimeBoolean(encoded: EncodedStepValue): Boolean =
        CoreIsUnixStep.definition.contract.outputCodec.decode(encoded).isUnix

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
    override suspend fun pwd(callSite: ScriptedCallSiteId, tmp: Boolean): String {
        val invoker = registryInvoker
            ?: throw dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation(
                "runtime-returning scripted steps require a registry invoker; " +
                    "wire ScriptedRegistryInvoker into this entry point runtime",
            )
        val identity = scope.identity
        val (stepKey, encodedInput) = if (tmp) {
            CorePwdTmpStep.KEY to CorePwdTmpStep.definition.contract.inputCodec.encode(PwdTmpInput)
        } else {
            CorePwdStep.KEY to CorePwdStep.definition.contract.inputCodec.encode(PwdInput(tmp = false))
        }
        val result = invoker.invoke(
            ScriptedRegistryCall(
                runId = identity.runId,
                entryPointId = identity.entryPointId,
                callSiteId = callSite,
                dynamicScopePath = identity.dynamicScopePath,
                invocationOrdinal = scope.nextOrdinal(callSite),
                stepKey = stepKey,
                encodedInput = encodedInput,
            ),
        )
        return when (result) {
            is ScriptedRegistryResult.Success -> try {
                decodeRuntimePwdPath(tmp, result.encodedOutput)
            } catch (e: IllegalArgumentException) {
                throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                    dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
                        "persisted runtime output is not decodable by the step's declared codec: ${e.message}",
                    ),
                )
            }
            is ScriptedRegistryResult.Failed -> throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                result.failure,
            )
        }
    }

    /**
     * Typed projection through the Step's DECLARED output codec. Both `core.pwd`
     * and `core.pwd.tmp` produce a `PwdOutput(path: String)`; the codec is
     * static Step-owned code so REUSE (empty registry, zero capabilities) can
     * decode the persisted payload with the SAME codec that encoded it fresh.
     */
    private fun decodeRuntimePwdPath(tmp: Boolean, encoded: EncodedStepValue): String {
        val codec = if (tmp) {
            CorePwdTmpStep.definition.contract.outputCodec
        } else {
            CorePwdStep.definition.contract.outputCodec
        }
        return codec.decode(encoded).path
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
        val invoker = registryInvoker
            ?: throw dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation(
                "runtime-returning scripted steps require a registry invoker; " +
                    "wire ScriptedRegistryInvoker into this entry point runtime",
            )
        val identity = scope.identity
        val definition = dev.rubentxu.pipeline.v2.application.CoreReadFileStep.definition
        val result = invoker.invoke(
            ScriptedRegistryCall(
                runId = identity.runId,
                entryPointId = identity.entryPointId,
                callSiteId = callSite,
                dynamicScopePath = identity.dynamicScopePath,
                invocationOrdinal = scope.nextOrdinal(callSite),
                stepKey = dev.rubentxu.pipeline.v2.application.CoreReadFileStep.KEY,
                encodedInput = definition.contract.inputCodec.encode(
                    dev.rubentxu.pipeline.v2.application.CoreReadFileInput(
                        file = file,
                        encoding = "UTF-8",
                    ),
                ),
            ),
        )
        return when (result) {
            is ScriptedRegistryResult.Success -> try {
                val output = definition.contract.outputCodec.decode(result.encodedOutput)
                if (!output.exists) {
                    throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                        dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                            dev.rubentxu.pipeline.v2.domain.FailureKind.USER,
                            "readFile($file) reported exists=false; runtime-returning façade cannot materialise content",
                        ),
                    )
                }
                output.content
                    ?: throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                        dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                            dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
                            "persisted readFile output has exists=true but content=null",
                        ),
                    )
            } catch (e: IllegalArgumentException) {
                throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                    dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
                        "persisted readFile output is not decodable by the step's declared codec: ${e.message}",
                    ),
                )
            }
            is ScriptedRegistryResult.Failed -> throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                result.failure,
            )
        }
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
    ): Boolean {
        val invoker = registryInvoker
            ?: throw dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation(
                "runtime-returning scripted steps require a registry invoker; " +
                    "wire ScriptedRegistryInvoker into this entry point runtime",
            )
        val identity = scope.identity
        val definition = dev.rubentxu.pipeline.v2.application.CoreFileExistsStep.definition
        val result = invoker.invoke(
            ScriptedRegistryCall(
                runId = identity.runId,
                entryPointId = identity.entryPointId,
                callSiteId = callSite,
                dynamicScopePath = identity.dynamicScopePath,
                invocationOrdinal = scope.nextOrdinal(callSite),
                stepKey = dev.rubentxu.pipeline.v2.application.CoreFileExistsStep.KEY,
                encodedInput = definition.contract.inputCodec.encode(
                    dev.rubentxu.pipeline.v2.application.CoreFileExistsInput(file = file),
                ),
            ),
        )
        return when (result) {
            is ScriptedRegistryResult.Success -> try {
                definition.contract.outputCodec.decode(result.encodedOutput).exists
            } catch (e: IllegalArgumentException) {
                throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                    dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
                        "persisted fileExists output is not decodable by the step's declared codec: ${e.message}",
                    ),
                )
            }
            is ScriptedRegistryResult.Failed -> throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                result.failure,
            )
        }
    }

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
        val invoker = registryInvoker
            ?: throw dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation(
                "runtime-returning scripted steps require a registry invoker; " +
                    "wire ScriptedRegistryInvoker into this entry point runtime",
            )
        val identity = scope.identity
        val definition = CoreShellStep.definition
        val input = CoreShellInput(
            command = ShellCommand(
                script = script,
                encoding = encoding,
                label = null,
                returnMode = ShellReturnMode.STDOUT,
            ),
        )
        val result = invoker.invoke(
            ScriptedRegistryCall(
                runId = identity.runId,
                entryPointId = identity.entryPointId,
                callSiteId = callSite,
                dynamicScopePath = identity.dynamicScopePath,
                invocationOrdinal = scope.nextOrdinal(callSite),
                stepKey = CoreShellStep.KEY,
                encodedInput = definition.contract.inputCodec.encode(input),
            ),
        )
        return when (result) {
            is ScriptedRegistryResult.Success -> try {
                val output = definition.contract.outputCodec.decode(result.encodedOutput)
                val stdoutValue = (output.result as? dev.rubentxu.pipeline.v2.domain.ShellInvocationResult.Stdout)?.value
                    ?: throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                        dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                            dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
                            "core.sh succeeded but the persisted output is not a Stdout variant " +
                                "(got ${output.result::class.simpleName}); the sh(returnStdout=true) facade requires ShellReturnMode.STDOUT",
                        ),
                    )
                stdoutValue
            } catch (e: IllegalArgumentException) {
                throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                    dev.rubentxu.pipeline.v2.domain.PipelineFailure(
                        dev.rubentxu.pipeline.v2.domain.FailureKind.REPLAY_COMPATIBILITY,
                        "persisted shReturnStdout output is not decodable by core.sh's declared codec: ${e.message}",
                    ),
                )
            }
            is ScriptedRegistryResult.Failed -> throw dev.rubentxu.pipeline.v2.domain.PipelineStepException(
                result.failure,
            )
        }
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
