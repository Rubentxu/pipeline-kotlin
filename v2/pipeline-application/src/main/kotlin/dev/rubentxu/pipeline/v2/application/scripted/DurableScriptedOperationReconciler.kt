package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.classifyShellTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellLaunching
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.terminalFromReconciliation
import java.nio.file.Files
import java.nio.file.Path

/**
 * Observes the stable durable control directory for a scripted operation.
 *
 * It never calls `launch`: a compatible RUNNING record is either completed by
 * observing its existing process or converted to a fail-closed terminal state.
 */
class DurableScriptedOperationReconciler(
    private val controlDirRoot: Path,
    private val clock: Clock,
    private val shell: DurableShellLaunching,
    private val config: DurableShConfig = DurableShConfig.fromSystemProperties(),
    private val reattachTimeoutMs: Long = DEFAULT_REATTACH_TIMEOUT_MS,
) : RunningScriptedOperationReconciler {
    override suspend fun reconcile(operation: ScriptedOperation): ScriptedRunningResolution {
        val opId = operation.operationId()
        val reconciler = StepReconcilerL1(clock, controlDirRoot, config)
        val classification = reconciler.classify(opId)
        val terminal = when (classification) {
            is StepReconcilerL1.Classification.Complete -> exited(controlDirRoot.resolve(opId), classification.exitCode)
            is StepReconcilerL1.Classification.Reattach -> {
                val exitCode = shell.pollResult(classification.controlDir, reattachTimeoutMs)
                if (exitCode == null) {
                    return failClosed("durable scripted shell did not publish a result while reattaching")
                }
                exited(classification.controlDir, exitCode)
            }
            is StepReconcilerL1.Classification.TimedOut,
            StepReconcilerL1.Classification.Lost,
            -> reconciler.terminalFromReconciliation(opId, controlDirRoot.resolve(opId))
                ?: return failClosed("durable scripted shell did not reach a terminal state")
        }
        val result = classifyShellTerminal(terminal, operation.command.returnMode)
        return ScriptedRunningResolution.Terminal(result, result.toOperationStatus())
    }

    private fun exited(controlDir: Path, exitCode: Int): DurableTaskTerminal.Exited = DurableTaskTerminal.Exited(
        exitCode = exitCode,
        output = DurableTaskOutput(
            controlDir = controlDir.toString(),
            capturedStdout = if (Files.exists(controlDir.resolve("output.txt"))) {
                Files.readString(controlDir.resolve("output.txt"))
            } else {
                null
            },
        ),
    )

    private fun failClosed(message: String): ScriptedRunningResolution.Terminal =
        ScriptedRunningResolution.Terminal(
            result = ShellInvocationResult.Failed(PipelineFailure(FailureKind.INFRASTRUCTURE, message)),
            status = OperationStatus.LOST,
        )

    private fun ShellInvocationResult.toOperationStatus(): OperationStatus = when (this) {
        ShellInvocationResult.UnitValue,
        is ShellInvocationResult.Stdout,
        is ShellInvocationResult.Status,
        -> OperationStatus.SUCCEEDED
        is ShellInvocationResult.Failed -> OperationStatus.FAILED
        is ShellInvocationResult.Interrupted -> if (interruption.kind == dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind.TIMEOUT) {
            OperationStatus.FAILED_TIMEOUT
        } else {
            OperationStatus.ABORTED
        }
    }

    private companion object {
        const val DEFAULT_REATTACH_TIMEOUT_MS = 60_000L
    }
}
