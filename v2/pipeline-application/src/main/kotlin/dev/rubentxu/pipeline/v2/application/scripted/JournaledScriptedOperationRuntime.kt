package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.durable.toStepOutcome
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Journal adapter that turns a scripted operation into an execute-or-replay decision.
 *
 * A compatible terminal entry is decoded without calling [effectRuntime]. A RUNNING
 * entry is delegated only to [runningReconciler], which is forbidden from relaunching
 * the effect and must return a terminal observation.
 */
class JournaledScriptedOperationRuntime(
    private val journal: OperationJournal,
    private val clock: Clock,
    private val effectRuntime: ScriptedOperationRuntime,
    private val runningReconciler: RunningScriptedOperationReconciler =
        RunningScriptedOperationReconciler { ScriptedRunningResolution.Unavailable },
) : ScriptedOperationRuntime {
    /**
     * S4-D2 — COMPATIBILITY ADAPTATION, not a redesign.
     *
     * The port now carries the outcome beside the value, so this legacy path has to supply one.
     * It supplies it by REUSING the single classifier authority, [toStepOutcome], on the very
     * value it already produced — it does not define a new mapping, and it does not branch on
     * any Step.
     *
     * The FRESH path is the exception: the inner [effectRuntime] already decided the outcome, so
     * that one is propagated verbatim. Re-deriving it here would be a second classification of a
     * fact that was never lost, which is the defect this slice exists to remove.
     *
     * KNOWN LIMITATION, unchanged and not introduced here: [toStepOutcome] has no `Unstable` arm
     * — `Status` classifies as `Success`. So on THIS path a replayed UNSTABLE shell still reports
     * `Success`. That is pre-existing behaviour of a class that production never constructs (see
     * the reachability note in the S4-D2 receipt), and closing it would mean giving the
     * classifier a `Unstable` arm, i.e. the second semantic implementation the owner ruled out.
     */
    override suspend fun invoke(operation: ScriptedOperation): ScriptedOperationResult {
        val input = operation.toOperationInput()
        val fingerprint = Fingerprint.compute(input, SCRIPTED_SHELL_STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT)
        val operationId = operation.operationId()
        val existing = journal.get(operationId)

        if (existing != null) {
            if (existing.fingerprint != fingerprint) return replayFailure("scripted operation input diverged")
            return existing.toReplayResult(operation, fingerprint)
        }

        val startedAt = clock.now().toEpochMilli()
        journal.append(operationRecord(operationId, fingerprint, input, OperationStatus.RUNNING, null))
        val settled = effectRuntime.invoke(operation)
        val output = OperationOutput(
            result = settled.value.toWire(),
            durationMs = (clock.now().toEpochMilli() - startedAt).coerceAtLeast(0),
            finishedAt = clock.now().toEpochMilli(),
        )
        journal.append(
            operationRecord(operationId, fingerprint, input, settled.value.toOperationStatus(), output),
        )
        return settled
    }

    private fun operationRecord(
        id: String,
        fingerprint: Fingerprint,
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

    private suspend fun DurableOperation.toReplayResult(
        operation: ScriptedOperation,
        fingerprint: Fingerprint,
    ): ScriptedOperationResult = when (status) {
        OperationStatus.SUCCEEDED,
        OperationStatus.FAILED,
        OperationStatus.ABORTED,
        OperationStatus.FAILED_TIMEOUT,
        OperationStatus.LOST,
        -> output?.result?.jsonObject?.toShellResult()?.classified()
            ?: replayFailure("terminal scripted operation has no serialized result")

        OperationStatus.RUNNING -> reconcileRunning(operation, fingerprint)

        OperationStatus.PENDING,
        OperationStatus.DIVERGENT,
        -> replayFailure("scripted operation is not safely replayable from status $status")
    }

    /** S4-D2: the ONE classifier, applied to the ONE value this path already produced. */
    private fun ShellInvocationResult.classified(): ScriptedOperationResult =
        ScriptedOperationResult(value = this, outcome = toStepOutcome())

    private suspend fun reconcileRunning(
        operation: ScriptedOperation,
        fingerprint: Fingerprint,
    ): ScriptedOperationResult = when (val resolution = runningReconciler.reconcile(operation)) {
        ScriptedRunningResolution.Unavailable ->
            replayFailure("scripted operation is RUNNING and no durable task can be reattached")

        is ScriptedRunningResolution.Terminal -> {
            val finishedAt = clock.now().toEpochMilli()
            val output = OperationOutput(
                result = resolution.result.toWire(),
                durationMs = 0,
                finishedAt = finishedAt,
            )
            journal.append(
                operationRecord(
                    id = operation.operationId(),
                    fingerprint = fingerprint,
                    input = operation.toOperationInput(),
                    status = resolution.status,
                    output = output,
                ),
            )
            resolution.result.classified()
        }
    }

    private fun ScriptedOperation.toOperationInput(): OperationInput = OperationInput(
        stepId = SCRIPTED_SHELL_STEP_ID,
        params = buildJsonObject {
            put("runId", runId)
            put("definitionDigest", definitionDigest)
            put("entryPointId", entryPointId)
            put("callSiteId", callSiteId.value)
            put("dynamicScopePath", dynamicScopePath.joinToString("/"))
            put("invocationOrdinal", invocationOrdinal)
            put("script", command.script)
            put("encoding", command.encoding)
            put("label", command.label)
            put("returnMode", command.returnMode.name)
        },
        runId = runId,
        attempt = ATTEMPT,
    )

    private fun ShellInvocationResult.toOperationStatus(): OperationStatus = when (this) {
        ShellInvocationResult.UnitValue,
        is ShellInvocationResult.Stdout,
        is ShellInvocationResult.Status,
        -> OperationStatus.SUCCEEDED

        is ShellInvocationResult.Failed -> OperationStatus.FAILED
        is ShellInvocationResult.Interrupted -> if (interruption.kind == InterruptionKind.TIMEOUT) {
            OperationStatus.FAILED_TIMEOUT
        } else {
            OperationStatus.ABORTED
        }
    }

    private fun ShellInvocationResult.toWire(): JsonObject = when (this) {
        ShellInvocationResult.UnitValue -> wire("UNIT")
        is ShellInvocationResult.Stdout -> wire("STDOUT") { put("value", value) }
        is ShellInvocationResult.Status -> wire("STATUS") { put("exitCode", exitCode) }
        is ShellInvocationResult.Failed -> wire("FAILED") {
            put("failureKind", failure.kind.name)
            put("message", failure.message)
            exitCode?.let { put("exitCode", it) }
            durableFailure?.let { put("durableFailure", it.toWire()) }
        }
        is ShellInvocationResult.Interrupted -> wire("INTERRUPTED") {
            put("interruptionKind", interruption.kind.name)
            put("message", interruption.message)
            put("operationId", interruption.operationId)
        }
    }

    private fun JsonObject.toShellResult(): ShellInvocationResult = when (this["kind"]?.jsonPrimitive?.content) {
        "UNIT" -> ShellInvocationResult.UnitValue
        "STDOUT" -> ShellInvocationResult.Stdout(requireText("value"))
        "STATUS" -> ShellInvocationResult.Status(requireText("exitCode").toInt())
        "FAILED" -> ShellInvocationResult.Failed(
            failure = PipelineFailure(FailureKind.valueOf(requireText("failureKind")), requireText("message")),
            durableFailure = this["durableFailure"]?.jsonObject?.toFailureRecord(),
            exitCode = this["exitCode"]?.jsonPrimitive?.content?.toInt(),
        )
        "INTERRUPTED" -> ShellInvocationResult.Interrupted(
            InterruptionRecord(
                kind = InterruptionKind.valueOf(requireText("interruptionKind")),
                message = requireText("message"),
                operationId = requireText("operationId"),
            ),
        )
        else -> replayWireFailure("unknown scripted result wire format")
    }

    private fun JsonObject.requireText(name: String): String = this[name]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Missing scripted result field '$name'")

    private fun FailureRecord.toWire(): JsonObject = buildJsonObject {
        put("code", code)
        put("kind", kind.name)
        put("message", message)
        put("origin", origin.name)
        put("retryable", retryable)
        put("operationId", operationId)
        workerId?.let { put("workerId", it) }
        taskId?.let { put("taskId", it) }
        put("schemaVersion", schemaVersion)
        put("details", buildJsonObject { details.forEach { (key, value) -> put(key, value) } })
    }

    private fun JsonObject.toFailureRecord(): FailureRecord = FailureRecord(
        code = requireText("code"),
        kind = FailureKind.valueOf(requireText("kind")),
        message = requireText("message"),
        origin = FailureOrigin.valueOf(requireText("origin")),
        retryable = requireText("retryable").toBooleanStrict(),
        operationId = requireText("operationId"),
        workerId = this["workerId"]?.jsonPrimitive?.content,
        taskId = this["taskId"]?.jsonPrimitive?.content,
        details = this["details"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
        schemaVersion = requireText("schemaVersion").toInt(),
    )

    private fun wire(kind: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("kind", kind)
        fields()
    }

    /**
     * S4-D2: the typed failure is built ONCE and the outcome is obtained from the single
     * classifier, so this method cannot encode a second opinion about what a replay failure is.
     */
    private fun replayFailure(message: String): ScriptedOperationResult =
        replayWireFailure(message).classified()

    /** The BARE failure, for the wire decoder, which returns a value and not a port result. */
    private fun replayWireFailure(message: String): ShellInvocationResult.Failed =
        ShellInvocationResult.Failed(
            PipelineFailure(FailureKind.REPLAY_COMPATIBILITY, message),
        )

    private companion object {
        const val ATTEMPT = 1
        const val SCRIPTED_SHELL_STEP_ID = "scripted.core.sh"
    }
}
