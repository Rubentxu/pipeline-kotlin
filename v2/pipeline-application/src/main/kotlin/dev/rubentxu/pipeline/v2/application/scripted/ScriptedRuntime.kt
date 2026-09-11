package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.InfrastructureStepException
import dev.rubentxu.pipeline.v2.domain.NetworkStepException
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PipelineStepException
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellExitException
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.UserStepException
import dev.rubentxu.pipeline.v2.scripting.ReturnStatus
import dev.rubentxu.pipeline.v2.scripting.ReturnStdout
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedDynamicScopeId

/** One effectful scripted shell invocation before the runtime decides replay or execution. */
data class ScriptedOperation(
    val definitionDigest: String,
    val runId: String = definitionDigest,
    val entryPointId: String,
    val callSiteId: ScriptedCallSiteId,
    val dynamicScopePath: List<String>,
    val invocationOrdinal: Int,
    val command: ShellCommand,
) {
    init {
        require(runId.isNotBlank()) { "Scripted run id must not be blank" }
        require(definitionDigest.isNotBlank()) { "Definition digest must not be blank" }
        require(entryPointId.isNotBlank()) { "Scripted entry point id must not be blank" }
        require(invocationOrdinal >= 0) { "Scripted invocation ordinal must not be negative" }
    }

    /** Stable durable identity for the same compiled call site and dynamic scope. */
    internal fun operationId(): String = stableScriptedKey(
        listOf(runId, entryPointId, callSiteId.value, dynamicScopePath.size.toString()) +
            dynamicScopePath + invocationOrdinal.toString(),
    )
}

/** Durable/replay adapter for a single scripted operation. */
fun interface ScriptedOperationRuntime {
    suspend fun invoke(operation: ScriptedOperation): ShellInvocationResult
}

/**
 * Reconciles an already scheduled scripted operation without invoking its effect
 * again. A resolver must return a terminal result only after it has observed one.
 */
fun interface RunningScriptedOperationReconciler {
    suspend fun reconcile(operation: ScriptedOperation): ScriptedRunningResolution
}

/** Closed result of attempting to recover a scripted operation left RUNNING. */
sealed interface ScriptedRunningResolution {
    data object Unavailable : ScriptedRunningResolution

    data class Terminal(
        val result: ShellInvocationResult,
        val status: dev.rubentxu.pipeline.v2.domain.durable.OperationStatus,
    ) : ScriptedRunningResolution
}

/** Internal source identity provider; generated façades replace the fixed test adapter. */
fun interface ScriptedCallSiteProvider {
    fun next(): ScriptedCallSiteId

    companion object {
        fun fixed(value: String): ScriptedCallSiteProvider = ScriptedCallSiteProvider { ScriptedCallSiteId(value) }
    }
}

/** Executes a compiled scripted entry point against a durable operation runtime. */
class ScriptedRuntime(
    private val operationRuntime: ScriptedOperationRuntime,
    private val callSites: ScriptedCallSiteProvider,
) {
    suspend fun <T> run(
        definitionDigest: String,
        entryPointId: String,
        runId: String = definitionDigest,
        block: suspend ScriptedScope.() -> T,
    ): T = ScriptedScope(
        runId = runId,
        definitionDigest = definitionDigest,
        entryPointId = entryPointId,
        operationRuntime = operationRuntime,
        callSites = callSites,
        dynamicScopePath = emptyList(),
        ordinals = mutableMapOf(),
    ).block()
}

/** Public runtime step façade. Values are materialized before control returns to Kotlin. */
class ScriptedScope internal constructor(
    private val runId: String,
    private val definitionDigest: String,
    private val entryPointId: String,
    private val operationRuntime: ScriptedOperationRuntime,
    private val callSites: ScriptedCallSiteProvider,
    private val dynamicScopePath: List<String>,
    private val ordinals: MutableMap<String, Int>,
) {
    suspend fun sh(
        script: String,
        encoding: String? = null,
        label: String? = null,
    ) {
        invoke(ShellCommand(script, encoding, label, ShellReturnMode.NONE)).asUnit()
    }

    suspend fun sh(
        script: String,
        returnStdout: ReturnStdout,
        encoding: String? = null,
        label: String? = null,
    ): String {
        return invoke(ShellCommand(script, encoding, label, ShellReturnMode.STDOUT)).asStdout()
    }

    suspend fun sh(
        script: String,
        returnStatus: ReturnStatus,
        encoding: String? = null,
        label: String? = null,
    ): Int {
        return invoke(ShellCommand(script, encoding, label, ShellReturnMode.STATUS)).asStatus()
    }

    private suspend fun invoke(command: ShellCommand): ShellInvocationResult = invokeAt(callSites.next(), command)

    internal suspend fun invokeAt(
        callSiteId: ScriptedCallSiteId,
        command: ShellCommand,
    ): ShellInvocationResult {
        val ordinalKey = stableScriptedKey(
            listOf(callSiteId.value, dynamicScopePath.size.toString()) + dynamicScopePath,
        )
        val ordinal = ordinals.getOrDefault(ordinalKey, 0)
        ordinals[ordinalKey] = ordinal + 1
        return operationRuntime.invoke(
            ScriptedOperation(
                runId = runId,
                definitionDigest = definitionDigest,
                entryPointId = entryPointId,
                callSiteId = callSiteId,
                dynamicScopePath = dynamicScopePath,
                invocationOrdinal = ordinal,
                command = command,
            ),
        )
    }

    internal suspend fun <T> scoped(
        scopeId: ScriptedDynamicScopeId,
        block: suspend ScriptedScope.() -> T,
    ): T = ScriptedScope(
        runId = runId,
        definitionDigest = definitionDigest,
        entryPointId = entryPointId,
        operationRuntime = operationRuntime,
        callSites = callSites,
        dynamicScopePath = dynamicScopePath + scopeId.value,
        ordinals = ordinals,
    ).block()

    /** Source identity for the registry invoker (LFC-2R / R2). */
    internal val identity: ScriptedScopeIdentity = ScriptedScopeIdentity(runId, entryPointId, dynamicScopePath)

    /**
     * Next invocation ordinal for one call site within this scope path — the same
     * loop-safety discipline as [invokeAt], shared by all registry-step invocations.
     */
    internal fun nextOrdinal(callSiteId: ScriptedCallSiteId): Int {
        val ordinalKey = stableScriptedKey(
            listOf(callSiteId.value, dynamicScopePath.size.toString()) + dynamicScopePath,
        )
        val ordinal = ordinals.getOrDefault(ordinalKey, 0)
        ordinals[ordinalKey] = ordinal + 1
        return ordinal
    }
}

/** Immutable identity inputs the runtime façade forwards to the registry invoker. */
internal data class ScriptedScopeIdentity(
    val runId: String,
    val entryPointId: String,
    val dynamicScopePath: List<String>,
)

internal fun ShellInvocationResult.asUnit() = when (this) {
    ShellInvocationResult.UnitValue -> Unit
    else -> throw incompatibleShellResult(ShellReturnMode.NONE, this)
}

internal fun ShellInvocationResult.asStdout(): String = when (this) {
    is ShellInvocationResult.Stdout -> value
    else -> throw incompatibleShellResult(ShellReturnMode.STDOUT, this)
}

internal fun ShellInvocationResult.asStatus(): Int = when (this) {
    is ShellInvocationResult.Status -> exitCode
    else -> throw incompatibleShellResult(ShellReturnMode.STATUS, this)
}

private fun incompatibleShellResult(
    expected: ShellReturnMode,
    actual: ShellInvocationResult,
): RuntimeException = when (actual) {
    is ShellInvocationResult.Failed -> actual.failure.toStepException()
    is ShellInvocationResult.Interrupted -> PipelineStepException(
        PipelineFailure(FailureKind.TIMEOUT, actual.interruption.message),
    )
    else -> EngineInvariantViolation("Shell runtime returned $actual for $expected mode")
}

private fun PipelineFailure.toStepException(): PipelineStepException = when (kind) {
    FailureKind.SCRIPT -> ShellExitException(this)
    FailureKind.INFRASTRUCTURE -> InfrastructureStepException(this)
    FailureKind.NETWORK -> NetworkStepException(this)
    FailureKind.USER -> UserStepException(this)
    FailureKind.PLUGIN -> PluginStepException(this)
    else -> PipelineStepException(this)
}

/** Length-prefix encoding keeps durable tuple identities unambiguous. */
internal fun stableScriptedKey(fields: List<String>): String = fields.joinToString(separator = "") { field ->
    "${field.length}:$field"
}
