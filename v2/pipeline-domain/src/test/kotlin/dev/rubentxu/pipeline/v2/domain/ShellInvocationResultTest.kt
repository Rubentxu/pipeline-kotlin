package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
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
}
