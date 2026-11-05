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
 * This is deliberately separate from [StepOutcome]: a status exit code can be
 * a successful shell value even when it is non-zero.
*/
sealed interface ShellInvocationResult {
    /**
     * The shell ran and produced no value because none was asked for.
     *
     * It means the command finished with code zero under the `NONE` mode:
     * the invocation succeeded and there is nothing to read.
     */
    data object UnitValue : ShellInvocationResult

    /**
     * The standard output of a shell that finished with code zero.
     *
     * It appears only under the `STDOUT` mode: a shell that exits non-zero
     * under that mode is a [Failed] rather than an empty output, so a
     * caller that receives this knows both that the command succeeded and
     * what it printed.
     */
    data class Stdout(val value: String) : ShellInvocationResult

    /**
     * The exit code of the shell.
     *
     * It appears under the `STATUS` mode, and a non-zero code is still a
     * delivered value rather than a failure: the mode asks for the code
     * precisely so that the caller can decide what it means. A shell that
     * failed before it could report a code is a [Failed].
     */
    data class Status(val exitCode: Int) : ShellInvocationResult

    /**
     * The invocation could not deliver the value it was asked for.
     *
     * The failure says why: it holds the classification, and the durable
     * record is present when the launcher or the reconciliation produced the
     * failure, and absent when the shell reported an exit code. The exit code
     * is present when the shell reported one.
     */
    data class Failed(
        val failure: PipelineFailure,
        val durableFailure: FailureRecord? = null,
        val exitCode: Int? = null,
    ) : ShellInvocationResult

    /**
     * The invocation was cancelled or interrupted before it could answer.
     *
     * It is not a failure: the record says why the work stopped, and
     * whether to resume, retry or give up is the caller's decision.
     */
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

/**
 * Pure projection from the two CLI boolean flags to a [ShellReturnMode].
 * The input space is `2 x 2 = 4` combinations; only 3 are legal (the
 * `(true, true)` case is rejected at the codec boundary because the shell
 * cannot simultaneously project stdout and a status code). The 2x2 -> 3
 * projection is split into a typed precondition ([require]) and a 3-way
 * `when` over a single boolean at a time, so future readers do not have
 * to re-derive the rejection from the silent collapse of an `else` arm.
 * `require` (not `check`) preserves the original `IllegalArgumentException`
 * contract that the `CoreShellStep` codec and its callers rely on.
*/
fun decideShellReturnMode(returnStdout: Boolean, returnStatus: Boolean): ShellReturnMode {
    require(!(returnStdout && returnStatus)) {
        "Shell payload cannot enable both returnStdout and returnStatus"
    }
    return when {
        returnStdout -> ShellReturnMode.STDOUT
        returnStatus -> ShellReturnMode.STATUS
        else -> ShellReturnMode.NONE
    }
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
