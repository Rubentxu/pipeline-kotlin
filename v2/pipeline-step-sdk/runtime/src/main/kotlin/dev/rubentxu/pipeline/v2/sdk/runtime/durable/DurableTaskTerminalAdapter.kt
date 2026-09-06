package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import java.nio.file.Path

/**
 * Converts a reconciliation observation to a terminal only when the
 * reconciler has established one. A fresh heartbeat remains non-terminal and
 * therefore produces `null`, never a fake terminal result.
 */
fun StepReconcilerL1.terminalFromReconciliation(
    opId: String,
    controlDir: Path,
): DurableTaskTerminal? = when (val classification = classifyControlDir(controlDir)) {
    is StepReconcilerL1.Classification.Complete -> DurableTaskTerminal.Exited(
        exitCode = classification.exitCode,
        output = DurableTaskOutput(controlDir.toString()),
    )

    is StepReconcilerL1.Classification.TimedOut -> DurableTaskTerminal.Cancelled(
        InterruptionRecord(
            kind = InterruptionKind.TIMEOUT,
            message = "durable task timed out",
            operationId = opId,
            details = mapOf("controlDir" to controlDir.toString()),
        ),
    )

    StepReconcilerL1.Classification.Lost -> DurableTaskTerminal.Lost(
        lostFailureRecord(opId, controlDir, "heartbeat is stale or durable result is unavailable"),
    )

    is StepReconcilerL1.Classification.Reattach -> null
}

/** Transitional adapter retained for PipelineRun's pre-EM-1 projection; remove in EM-10. */
@Deprecated(
    message = "Legacy durable-shell projection. Consume DurableTaskTerminal directly; removal is scheduled for EM-10.",
)
fun DurableTaskTerminal.toLegacyShellResult(controlDir: Path): DurableShellResult = when (this) {
    is DurableTaskTerminal.Exited -> DurableShellResult(
        state = DurableShellState.COMPLETE,
        exitCode = exitCode,
        controlDir = controlDir,
        capturedStdout = output.capturedStdout,
    )

    is DurableTaskTerminal.LaunchFailed -> DurableShellResult(
        state = DurableShellState.LAUNCH_FAILED,
        exitCode = -1,
        controlDir = controlDir,
    )

    is DurableTaskTerminal.Lost -> DurableShellResult(
        state = DurableShellState.LOST,
        exitCode = -1,
        controlDir = controlDir,
    )

    is DurableTaskTerminal.Cancelled -> DurableShellResult(
        state = DurableShellState.TIMED_OUT,
        exitCode = -1,
        controlDir = controlDir,
    )
}

/**
 * Adapts the legacy mixed result only after it is terminal. This is kept for
 * the two legacy entry points while their callers migrate to
 * [DurableShellExecutor.executeTerminal].
 */
@Deprecated(
    message = "Legacy durable-shell adapter. Produce DurableTaskTerminal at the executor seam; removal is scheduled for EM-10.",
)
fun DurableShellResult.toDurableTaskTerminal(opId: String): DurableTaskTerminal = when (state) {
    DurableShellState.COMPLETE -> DurableTaskTerminal.Exited(
        exitCode = exitCode,
        output = DurableTaskOutput(controlDir.toString(), capturedStdout),
    )

    DurableShellState.LAUNCH_FAILED -> DurableTaskTerminal.LaunchFailed(
        FailureRecord(
            code = "DURABLE_LAUNCH_FAILED",
            kind = FailureKind.INFRASTRUCTURE,
            message = "legacy durable shell launch failed",
            origin = FailureOrigin.LAUNCHER,
            retryable = false,
            operationId = opId,
            workerId = "local",
            taskId = "durable-shell",
            details = mapOf("controlDir" to controlDir.toString()),
        ),
    )

    DurableShellState.LOST -> DurableTaskTerminal.Lost(
        lostFailureRecord(opId, controlDir, "legacy durable shell result was lost"),
    )

    DurableShellState.TIMED_OUT -> DurableTaskTerminal.Cancelled(
        InterruptionRecord(
            kind = InterruptionKind.TIMEOUT,
            message = "legacy durable shell timed out",
            operationId = opId,
            details = mapOf("controlDir" to controlDir.toString()),
        ),
    )

    DurableShellState.LAUNCHING,
    DurableShellState.RUNNING,
    -> throw IllegalStateException("A legacy non-terminal result cannot be adapted as terminal: $state")
}

/**
 * Compatibility bridge used by the pre-EM-1 entry points. It makes the
 * terminal conversion explicit while preserving their existing result shape.
 */
@Suppress("DEPRECATION")
@Deprecated(
    message = "Legacy durable-shell compatibility bridge. Consume DurableTaskTerminal directly; removal is scheduled for EM-10.",
)
fun DurableShellResult.throughTerminalAdapter(opId: String): DurableShellResult =
    toDurableTaskTerminal(opId).toLegacyShellResult(controlDir)

internal fun Exception.launchFailureRecord(opId: String, controlDir: Path): FailureRecord = FailureRecord(
    code = "DURABLE_LAUNCH_FAILED",
    kind = FailureKind.INFRASTRUCTURE,
    message = message ?: "could not launch durable task",
    origin = FailureOrigin.LAUNCHER,
    retryable = false,
    operationId = opId,
    workerId = "local",
    taskId = "durable-shell",
    details = mapOf("controlDir" to controlDir.toString()),
)

internal fun lostFailureRecord(opId: String, controlDir: Path, message: String): FailureRecord = FailureRecord(
    code = "DURABLE_TASK_LOST",
    kind = FailureKind.INFRASTRUCTURE,
    message = message,
    origin = FailureOrigin.RECONCILIATION,
    retryable = true,
    operationId = opId,
    workerId = "local",
    taskId = "durable-shell",
    details = mapOf("controlDir" to controlDir.toString()),
)
