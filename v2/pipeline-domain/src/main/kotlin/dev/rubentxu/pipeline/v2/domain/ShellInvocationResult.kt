package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord

/** The observable value contract selected by a Jenkins-compatible shell invocation. */
enum class ShellReturnMode {
    NONE,
    STDOUT,
    STATUS,
}

/** Canonical shell input. Context-owned deadline and environment are excluded. */
data class ShellCommand(
    val script: String,
    val encoding: String? = null,
    val label: String? = null,
    val returnMode: ShellReturnMode = ShellReturnMode.NONE,
)

/**
 * Closed result algebra for shell invocation semantics.
 *
 * This is deliberately separate from [StepOutcome]: a status exit code can be
 * a successful shell value even when it is non-zero.
 */
sealed interface ShellInvocationResult {
    data object UnitValue : ShellInvocationResult

    data class Stdout(val value: String) : ShellInvocationResult

    data class Status(val exitCode: Int) : ShellInvocationResult

    data class Failed(
        val failure: PipelineFailure,
        val durableFailure: FailureRecord? = null,
        val exitCode: Int? = null,
    ) : ShellInvocationResult

    data class Interrupted(val interruption: InterruptionRecord) : ShellInvocationResult
}

/** Pure mapping from a terminal durable task state to the selected shell contract. */
fun classifyShellTerminal(
    terminal: DurableTaskTerminal,
    returnMode: ShellReturnMode,
): ShellInvocationResult = when (terminal) {
    is DurableTaskTerminal.Exited -> when (returnMode) {
        ShellReturnMode.STATUS -> ShellInvocationResult.Status(terminal.exitCode)
        ShellReturnMode.STDOUT -> if (terminal.exitCode == 0) {
            ShellInvocationResult.Stdout(terminal.output.capturedStdout.orEmpty())
        } else {
            terminal.scriptFailure()
        }
        ShellReturnMode.NONE -> if (terminal.exitCode == 0) {
            ShellInvocationResult.UnitValue
        } else {
            terminal.scriptFailure()
        }
    }

    is DurableTaskTerminal.LaunchFailed -> terminal.failure.asShellFailure()
    is DurableTaskTerminal.Lost -> terminal.failure.asShellFailure()
    is DurableTaskTerminal.Cancelled -> ShellInvocationResult.Interrupted(terminal.interruption)
}

private fun DurableTaskTerminal.Exited.scriptFailure(): ShellInvocationResult.Failed =
    ShellInvocationResult.Failed(
        PipelineFailure(FailureKind.SCRIPT, "shell exited with code $exitCode"),
        exitCode = exitCode,
    )

private fun FailureRecord.asShellFailure(): ShellInvocationResult.Failed =
    ShellInvocationResult.Failed(
        failure = PipelineFailure(kind, message),
        durableFailure = this,
    )
