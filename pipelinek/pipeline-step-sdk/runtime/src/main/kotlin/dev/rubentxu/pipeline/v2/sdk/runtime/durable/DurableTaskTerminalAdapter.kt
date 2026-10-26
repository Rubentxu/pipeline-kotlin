package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellFiles
import java.nio.file.Path

/** Transitional adapter retained for PipelineRun's projection; remove */
@Deprecated(
    message = "Legacy durable-shell projection. Consume DurableTaskTerminal directly; this projection is on its way out.",
)
fun DurableTaskTerminal.toLegacyShellResult(controlDir: Path): DurableShellResult = when (this) {
    is DurableTaskTerminal.Exited -> {
        // The canonical executor no longer
        // materialises the console transcript in JENKINS_LOG projection; the
        // legacy projection recovers it lazily from the durable console.log
        // (jenkins-log.txt) when the terminal carried no typed value. The
        // typed value channel (output.txt / capturedStdout) is unaffected.
        val legacyCaptured = output.capturedStdout
            ?: run {
                val consoleLog = DurableShellFiles.resolveConsoleLog(controlDir)
                if (java.nio.file.Files.exists(consoleLog)) {
                    java.nio.file.Files.readString(consoleLog)
                } else {
                    null
                }
            }
        DurableShellResult(
            state = DurableShellState.COMPLETE,
            exitCode = exitCode,
            controlDir = controlDir,
            capturedStdout = legacyCaptured,
        )
    }

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

