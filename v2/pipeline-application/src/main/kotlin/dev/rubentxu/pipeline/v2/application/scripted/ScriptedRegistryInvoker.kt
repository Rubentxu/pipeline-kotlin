package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Closed outcome of one scripted registry-step invocation. Never a fabricated value. */
sealed interface ScriptedRegistryResult {
    /** The step succeeded; [encodedOutput] is the step's typed output in wire form. */
    data class Success(val encodedOutput: EncodedStepValue) : ScriptedRegistryResult

    /** The step executed (fresh or reconciled) and failed; carries the typed failure. */
    data class Failed(val failure: PipelineFailure) : ScriptedRegistryResult
}

/**
 * Durable identity of one scripted registry-step call site (LFC-2R / R1).
 *
 * Reuses EXACTLY the scripted identity model proven for `sh` (runId, entry point,
 * call site, dynamic scope path, per-scope invocation ordinal) — plus the generic
 * [stepKey]. No Step-specific identity is ever created.
 */
data class ScriptedRegistryCall(
    val runId: String,
    val entryPointId: String,
    val callSiteId: ScriptedCallSiteId,
    val dynamicScopePath: List<String>,
    val invocationOrdinal: Int,
    val stepKey: PluginStepId,
    val encodedInput: EncodedStepValue,
) {
    init {
        require(runId.isNotBlank()) { "Scripted run id must not be blank" }
        require(entryPointId.isNotBlank()) { "Scripted entry point id must not be blank" }
        require(invocationOrdinal >= 0) { "Scripted invocation ordinal must not be negative" }
    }

    /** Stable durable operation identity — same tuple discipline as ScriptedOperation. */
    internal fun operationId(): String = stableScriptedKey(
        listOf(runId, entryPointId, callSiteId.value, dynamicScopePath.size.toString()) +
            dynamicScopePath + invocationOrdinal.toString() + stepKey.value,
    )
}

/**
 * LFC-2R / R1 — THE generic scripted→registry seam.
 *
 * Responsibility (and NOTHING else): call-site identity + step identity + encoded
 * input → durable execute-or-reuse → encoded output. The invoker is STEP-AGNOSTIC:
 * it must never name or special-case any concrete Step. Architecture fitness
 * enforces that by scanning this source with comments stripped.
 *
 * Fresh/reuse law (the gate's core property):
 * - FRESH: prepare/admit (fail-closed on capabilities BEFORE any effect) → execute the
 *   typed handler exactly once through [RegistryExecutionBoundary] → journal
 *   SUCCEEDED with the encodedOutput → return it decoded.
 * - REUSE: journal SUCCEEDED found for the same durable identity → handler = 0,
 *   capabilities/effects NOT consulted → persisted output decoded → returned.
 *   `reuse_value == persisted_value` and observation count == 0.
 *
 * Error model: FAIL-CLOSED, never a fabricated output. A SUCCEEDED row without
 * output, an undecodable output, an unknown key, a decode failure, a replay
 * divergence, or a capability-admission failure is an explicit
 * [ScriptedRegistryResult.Failed] carrying an engine/protocol [PipelineFailure] —
 * because the decoded value controls user Kotlin control flow (`if (isUnix())`).
 *
 * Replay does NOT require capabilities: the durable decision (journal lookup) happens
 * BEFORE any capability admission, so a resume never reconstructs or consults runtime
 * capabilities just to discover that the operation already succeeded.
 */
class ScriptedRegistryInvoker(
    private val registry: StepRegistry,
    private val journal: OperationJournal,
    private val clock: Clock,
    private val runtimeContextFactory: (call: ScriptedRegistryCall) -> CanonicalRuntimeContext,
) {

    suspend fun invoke(call: ScriptedRegistryCall): ScriptedRegistryResult {
        val stepId = scriptedStepId(call.stepKey)
        val input = OperationInput(
            stepId = stepId,
            params = buildJsonObject {
                put("runId", JsonPrimitive(call.runId))
                put("entryPointId", JsonPrimitive(call.entryPointId))
                put("callSiteId", JsonPrimitive(call.callSiteId.value))
                put("dynamicScopePath", JsonPrimitive(call.dynamicScopePath.joinToString("/")))
                put("invocationOrdinal", JsonPrimitive(call.invocationOrdinal))
                put("encodedInput", JsonPrimitive(call.encodedInput.value))
            }.let(::flattenParams),
            runId = call.runId,
            attempt = ATTEMPT,
        )
        val fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(
            input, stepId, ReplayPolicy.MEMOIZED, ATTEMPT,
        )
        val operationId = call.operationId()

        // Durable decision FIRST: reuse must not require capabilities or any effect.
        val existing = journal.get(operationId)
        if (existing != null) {
            if (existing.fingerprint != fingerprint) {
                return ScriptedRegistryResult.Failed(
                    PipelineFailure(FailureKind.REPLAY_COMPATIBILITY, "scripted registry step input diverged"),
                )
            }
            return when (existing.status) {
                OperationStatus.SUCCEEDED -> restoredOutput(existing.output)
                OperationStatus.RUNNING -> ScriptedRegistryResult.Failed(
                    PipelineFailure(
                        FailureKind.REPLAY_COMPATIBILITY,
                        "scripted registry step is RUNNING and no durable task can be reattached",
                    ),
                )
                OperationStatus.FAILED,
                OperationStatus.ABORTED,
                OperationStatus.FAILED_TIMEOUT,
                OperationStatus.LOST,
                -> ScriptedRegistryResult.Failed(
                    PipelineFailure(
                        FailureKind.REPLAY_COMPATIBILITY,
                        "scripted registry step previously ended $${existing.status.name}",
                    ),
                )
                OperationStatus.PENDING,
                OperationStatus.DIVERGENT,
                -> ScriptedRegistryResult.Failed(
                    PipelineFailure(
                        FailureKind.REPLAY_COMPATIBILITY,
                        "scripted registry step is not safely replayable from status ${existing.status.name}",
                    ),
                )
            }
        }

        // FRESH: admission (fail-closed) BEFORE any effect. Capabilities are consulted
        // only here — never on the reuse path above.
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = call.stepKey,
            encodedInput = call.encodedInput,
            availableCapabilities = dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess(
                runtimeContextFactory(call),
            ).available(),
        )
        val ready = when (preparation) {
            is ExecutionPreparation.Ready -> preparation.prepared as PreparedRegistryExecution
            is ExecutionPreparation.Rejected -> return ScriptedRegistryResult.Failed(
                PipelineFailure(FailureKind.SCHEMA, preparation.reason),
            )
        }

        journal.append(record(operationId, fingerprint, input, OperationStatus.RUNNING, null))
        val startedAt = clock.now().toEpochMilli()
        val result = RegistryExecutionBoundary.coexecute(ready, runtimeContextFactory(call))
        val finishedAt = clock.now().toEpochMilli()
        val output = result.encodedOutput?.let {
            OperationOutput(
                result = JsonPrimitive(it.value),
                durationMs = (finishedAt - startedAt).coerceAtLeast(0),
                finishedAt = finishedAt,
            )
        }
        val status = when (result.outcome) {
            is dev.rubentxu.pipeline.v2.domain.StepOutcome.Success -> OperationStatus.SUCCEEDED
            is dev.rubentxu.pipeline.v2.domain.StepOutcome.Unstable -> OperationStatus.SUCCEEDED
            is dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure -> OperationStatus.FAILED
        }
        journal.append(record(operationId, fingerprint, input, status, output))
        return when (result.outcome) {
            is dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure ->
                ScriptedRegistryResult.Failed(failureOf(result.outcome))
            // A runtime-returning Step MUST produce a value: SUCCEEDED with no encoded
            // output is a protocol violation, never a fabricated Unit/false.
            else -> when (val encoded = result.encodedOutput) {
                null -> ScriptedRegistryResult.Failed(
                    PipelineFailure(
                        FailureKind.ENGINE,
                        "scripted registry step succeeded without a typed runtime output",
                    ),
                )
                else -> ScriptedRegistryResult.Success(encoded)
            }
        }
    }

    private fun restoredOutput(output: dev.rubentxu.pipeline.v2.domain.durable.OperationOutput?): ScriptedRegistryResult =
        when (val raw = output?.result) {
            null -> ScriptedRegistryResult.Failed(
                PipelineFailure(
                    FailureKind.REPLAY_COMPATIBILITY,
                    "SUCCEEDED scripted registry step has no persisted output",
                ),
            )
            else -> ScriptedRegistryResult.Success(EncodedStepValue((raw as JsonPrimitive).content))
        }

    private fun record(
        id: String,
        fingerprint: dev.rubentxu.pipeline.v2.domain.durable.Fingerprint,
        input: OperationInput,
        status: OperationStatus,
        output: OperationOutput?,
    ): MemoizedOperation = MemoizedOperation(
        id = id,
        fingerprint = fingerprint,
        input = input,
        output = output,
        status = status,
        attempt = ATTEMPT,
        cachedOutput = output,
    )

    private fun failureOf(outcome: dev.rubentxu.pipeline.v2.domain.StepOutcome): PipelineFailure =
        (outcome as dev.rubentxu.pipeline.v2.domain.StepOutcome.Failure).failure

    /** `OperationInput.params` is Map<String, JsonElement>; keep the flat object form. */
    private fun flattenParams(obj: JsonObject): Map<String, kotlinx.serialization.json.JsonElement> = obj

    private companion object {
        const val ATTEMPT = 1

        /** Namespace rule: `scripted.` + stepKey; collision-checked by fitness tests. */
        fun scriptedStepId(key: PluginStepId): String = "scripted." + key.value
    }
}
