package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShellInvocationResultTest {
    @Test
    fun `default shell failure retains its nonzero exit code structurally`() {
        val result = classifyShellTerminal(
            DurableTaskTerminal.Exited(
                exitCode = 7,
                output = DurableTaskOutput(controlDir = "/tmp/sh"),
            ),
            ShellReturnMode.NONE,
        )

        val failure = result as ShellInvocationResult.Failed
        assertEquals(7, failure.exitCode)
    }

    @Test
    fun `returnStatus preserves a nonzero exit as a successful status value`() {
        val result = classifyShellTerminal(
            DurableTaskTerminal.Exited(
                exitCode = 42,
                output = DurableTaskOutput(controlDir = "/tmp/shell-status"),
            ),
            ShellReturnMode.STATUS,
        )

        assertEquals(ShellInvocationResult.Status(42), result)
    }

    /**
     * UAT-JEP-005: returnStdout=true — stderr output does NOT bleed into `ShellInvocationResult.Stdout.value`.
     * Pure classifyShellTerminal oracle: capturedStdout is the only source for Stdout.value.
     */
    @Test
    fun `returnStdout true with zero exit captures stdout without stderr bleed`() {
        val result = classifyShellTerminal(
            DurableTaskTerminal.Exited(
                exitCode = 0,
                output = DurableTaskOutput(controlDir = "/tmp/sh-stdout-test", capturedStdout = "build output\n"),
            ),
            ShellReturnMode.STDOUT,
        )

        assertEquals(ShellInvocationResult.Stdout("build output\n"), result)
    }

    /**
     * UAT-JEP-007: Lost terminal with FailureRecord classifies to INFRASTRUCTURE `Failed`
     * with `durableFailure` preserved (data-class equality).
     * Design: D4/R-D2 — adapter no-StepFailed half honored via INFRASTRUCTURE classification.
     */
    @Test
    fun `lost terminal with infrastructure failure record yields Failed with durableFailure preserved`() {
        val failureRecord = FailureRecord(
            code = "LOST_001",
            kind = FailureKind.INFRASTRUCTURE,
            message = "task process lost — heartbeat timeout",
            origin = FailureOrigin.DURABLE_TASK,
            retryable = false,
            operationId = "op-lost-001",
        )
        val terminal = DurableTaskTerminal.Lost(failureRecord)

        val result = classifyShellTerminal(terminal, ShellReturnMode.NONE)

        val failed = result as ShellInvocationResult.Failed
        assertEquals(FailureKind.INFRASTRUCTURE, failed.failure.kind)
        assertEquals(failureRecord, failed.durableFailure)
    }

    /**
     * UAT-JEP-010: Cancelled(USER_ABORT) classifies to `Interrupted`.
     * Design: D4/R-D2 — adapter no-StepFailed half is honored by ADT mapping.
     * Adapter citation: `PipelineRun.kt:1889-1910`, `ShExecution.kt:455-477`.
     */
    @Test
    fun `cancelled with user abort classifies to Interrupted`() {
        val interruption = InterruptionRecord(
            kind = InterruptionKind.USER_ABORT,
            message = "user cancelled the run",
            operationId = "op-cancelled-001",
        )
        val terminal = DurableTaskTerminal.Cancelled(interruption)

        val result = classifyShellTerminal(terminal, ShellReturnMode.NONE)

        assertEquals(ShellInvocationResult.Interrupted(interruption), result)
    }
}
