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
import dev.rubentxu.pipeline.v2.domain.StepOutcome
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

/**
 * S4-D2 — what ONE scripted operation hands back: the value the Kotlin program receives, and the
 * canonical outcome the durable substrate already decided.
 *
 * Before this type the port returned a bare [ShellInvocationResult], and that narrowing is
 * precisely where the outcome was lost: `RegistryScriptedShellRuntime` decoded a
 * `CoreShellOutput` — which carries `outcome` — and threw the outcome away to fit the port's
 * shape. The value and the outcome are different concepts, and a Step may legitimately be both
 * useful and `Unstable`, so the carrier has to hold both.
 *
 * ## Unlike [ScriptedTypedResult], this one cannot derive its own outcome
 *
 * `ShellInvocationResult` carries no `Unstable` case, so the field genuinely adds information
 * rather than duplicating it. That also means the pair CANNOT be self-validating, so the
 * discipline is structural: production constructs it in exactly ONE place —
 * [RegistryScriptedShellRuntime], projecting the [ScriptedTypedResult] it already received. No
 * other production site writes a `(result, outcome)` pair by hand, because a second one is where
 * a second opinion would appear.
 */
data class ScriptedOperationResult(
    val value: ShellInvocationResult,
    val outcome: StepOutcome,
)

/**
 * S4-D2 — the single run-local accumulator of outcomes that are ALREADY classified.
 *
 * It is deliberately not a decision maker. It does not know any Step, does not decide precedence,
 * does not convert `Unstable` into anything, does not read the journal, and does not hold a
 * `Boolean unstable`. Precedence belongs to [dev.rubentxu.pipeline.v2.domain.RunOutcomeReducer]
 * and to nothing else; this type only hands it the list.
 *
 * ## Why the owner is the caller and not the scope
 *
 * The collector is created by `runBody` and passed in, never constructed inside
 * [ScriptedRuntime.run]. If it were created inside `run`, a body that threw
 * [PipelineStepException] would abort the scope that owns it, and the `catch` in `runBody` could
 * not reach a snapshot — so the failure would never reach the reducer. Owning it outside is what
 * lets one reducer see every outcome including the one that ended the body.
 *
 * Child scopes receive the SAME instance by reference, exactly as [ScriptedScope] already shares
 * its `ordinals` map, so an outcome recorded inside a dynamic scope survives on return to the
 * parent.
 */
class ScriptedOutcomeCollector {
    private val recorded: MutableList<StepOutcome> = mutableListOf()

    /** Records one already-classified outcome. It is never re-interpreted here. */
    fun record(outcome: StepOutcome) {
        recorded += outcome
    }

    /** The outcomes in execution order, for the single reduction at the end of the body. */
    fun snapshot(): List<StepOutcome> = recorded.toList()
}

/**
 * Durable/replay adapter for a single scripted operation.
 *
 * S4-D2: returns [ScriptedOperationResult], not a bare [ShellInvocationResult], so the semantic
 * outcome survives the seam. The value alone cannot express "this succeeded but is unstable".
 */
fun interface ScriptedOperationRuntime {
    suspend fun invoke(operation: ScriptedOperation): ScriptedOperationResult
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
        outcomes: ScriptedOutcomeCollector = ScriptedOutcomeCollector(),
        block: suspend ScriptedScope.() -> T,
    ): T = ScriptedScope(
        runId = runId,
        definitionDigest = definitionDigest,
        entryPointId = entryPointId,
        operationRuntime = operationRuntime,
        callSites = callSites,
        dynamicScopePath = emptyList(),
        ordinals = mutableMapOf(),
        outcomes = outcomes,
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
    private val outcomes: ScriptedOutcomeCollector,
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
        val settled = operationRuntime.invoke(
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
        // S4-D2: the outcome is recorded HERE, at the point that sees every shell invocation, and
        // the Kotlin program receives only the value. The scope owns the collector; the runtime
        // never learns it exists, and never decides what the outcome means.
        outcomes.record(settled.outcome)
        return settled.value
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
        outcomes = outcomes,
    ).block()

    /**
     * S4-D2 — the single registration point for a RUNTIME-RETURNING scripted step.
     *
     * The façade is the only place that sees every per-call result (see
     * [dev.rubentxu.pipeline.v2.application.scripted.CompiledScriptedEntryPoint]), so it is where
     * an outcome is recorded. The scope owns the collector; the façade never decides precedence
     * and never converts anything.
     */
    internal fun recordOutcome(outcome: StepOutcome) {
        outcomes.record(outcome)
    }

    /** Source identity for the registry invoker (LFC-2R / R2). */
    internal val identity: ScriptedScopeIdentity =
        ScriptedScopeIdentity(runId, entryPointId, dynamicScopePath, definitionDigest)

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
    /**
     * S4-A1: the compiled artifact identity this execution came from.
     *
     * The retired `JournaledScriptedOperationRuntime` put this into its
     * `OperationInput`, so two different compiled artifacts of the same source
     * position produced different fingerprints and the second run failed closed
     * with REPLAY_COMPATIBILITY. Unifying the spine dropped it, which meant a
     * scripted registry step would replay happily across two different artifacts.
     * `ScriptedScopeTest` caught the regression, and the property is restored here
     * rather than the test being relaxed: unifying spines must not lose a
     * guarantee the retired spine had.
     */
    val definitionDigest: String,
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
