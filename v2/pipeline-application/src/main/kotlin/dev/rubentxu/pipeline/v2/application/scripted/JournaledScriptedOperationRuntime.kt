package dev.rubentxu.pipeline.v2.application.scripted

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
    override suspend fun invoke(operation: ScriptedOperation): ShellInvocationResult {
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
        val result = effectRuntime.invoke(operation)
        val output = OperationOutput(
            result = result.toWire(),
            durationMs = (clock.now().toEpochMilli() - startedAt).coerceAtLeast(0),
            finishedAt = clock.now().toEpochMilli(),
        )
        journal.append(operationRecord(operationId, fingerprint, input, result.toOperationStatus(), output))
        return result
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
    ): ShellInvocationResult = when (status) {
        OperationStatus.SUCCEEDED,
        OperationStatus.FAILED,
        OperationStatus.ABORTED,
        OperationStatus.FAILED_TIMEOUT,
        OperationStatus.LOST,
        -> output?.result?.jsonObject?.toShellResult()
            ?: replayFailure("terminal scripted operation has no serialized result")

        OperationStatus.RUNNING -> reconcileRunning(operation, fingerprint)

        OperationStatus.PENDING,
        OperationStatus.DIVERGENT,
        -> replayFailure("scripted operation is not safely replayable from status $status")
    }

    private suspend fun reconcileRunning(
        operation: ScriptedOperation,
        fingerprint: Fingerprint,
    ): ShellInvocationResult = when (val resolution = runningReconciler.reconcile(operation)) {
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
            resolution.result
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
        else -> replayFailure("unknown scripted result wire format")
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

    private fun replayFailure(message: String): ShellInvocationResult.Failed = ShellInvocationResult.Failed(
        PipelineFailure(FailureKind.REPLAY_COMPATIBILITY, message),
    )

    private companion object {
        const val ATTEMPT = 1
        const val SCRIPTED_SHELL_STEP_ID = "scripted.core.sh"
    }
}
