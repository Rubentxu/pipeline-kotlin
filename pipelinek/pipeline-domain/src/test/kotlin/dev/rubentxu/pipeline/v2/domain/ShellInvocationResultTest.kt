package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The guarantee: whether a non-zero exit is a FAILURE or a VALUE depends on the
 * return mode the caller asked for, and nothing else.
 * The capacity this serves: `pipeline console` / a step that requested `returnStatus` treats a
 * non-zero code as the answer to its question — "what did the tool print on exit", which is how
 * `grep -c 0` returns 1 and still means success. Under `NONE` or `STDOUT` the same code is a
 * failure. That asymmetry is the whole reason this algebra exists; the type KDoc says a status
 * exit code "can be a successful shell value even when it is non-zero". Collapse it and every
 * caller either fails healthy commands or stops seeing the codes it asked for.
 * Both mappings here sit on a published boundary that production callers depend on, and neither
 * had any coverage of its own.
 * [decideShellReturnMode] is pinned as the closure it claims: four boolean combinations, three
 * legal, and the fourth REJECTED rather than silently collapsed into one of the three. A silent
 * collapse would be the defect this row exists to prevent, so the rejection is asserted with its
 * exception type, not just its absence.
*/
class ShellInvocationResultTest {

    private fun exited(exitCode: Int, stdout: String? = null) =
        DurableTaskTerminal.Exited(exitCode, DurableTaskOutput(controlDir = "/tmp/ctl", capturedStdout = stdout))

    @Test
    fun `a non-zero exit is a value under STATUS and a failure under NONE`() {
        // The same terminal state, two different answers. This is the row that breaks silently.
        val underStatus = classifyShellTerminal(exited(exitCode = 1), ShellReturnMode.STATUS)
        val underNone = classifyShellTerminal(exited(exitCode = 1), ShellReturnMode.NONE)

        assertEquals(
            ShellInvocationResult.Status(1),
            underStatus,
            "under STATUS the exit code is the answer, not a verdict",
        )
        val failure = assertInstanceOf(ShellInvocationResult.Failed::class.java, underNone)
        assertEquals(1, failure.exitCode, "under NONE a non-zero exit must keep the code that caused it")
    }

    @Test
    fun `a non-zero exit under STDOUT is a failure, not an empty stdout`() {
        // A naive mapping answers Stdout("") here and loses the reason the command failed.
        val result = classifyShellTerminal(exited(exitCode = 3, stdout = "partial"), ShellReturnMode.STDOUT)

        val failure = assertInstanceOf(ShellInvocationResult.Failed::class.java, result)
        assertEquals(
            3,
            failure.exitCode,
            "the captured stdout must not turn a failed command into a successful-looking value",
        )
    }

    @Test
    fun `a zero exit projects onto the shape the caller asked for`() {
        assertEquals(
            ShellInvocationResult.UnitValue,
            classifyShellTerminal(exited(0), ShellReturnMode.NONE),
            "NONE discards the payload entirely",
        )
        assertEquals(
            ShellInvocationResult.Stdout("hello"),
            classifyShellTerminal(exited(0, stdout = "hello"), ShellReturnMode.STDOUT),
            "STDOUT carries the captured bytes",
        )
        assertEquals(
            ShellInvocationResult.Status(0),
            classifyShellTerminal(exited(0), ShellReturnMode.STATUS),
            "STATUS reports the code, which here is zero",
        )
    }

    @Test
    fun `enabling both returnStdout and returnStatus is rejected, not collapsed`() {
        // The fourth combination out of four. Silently preferring one would make the CLI's two
        // flags lie about each other with no error anywhere.
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            decideShellReturnMode(returnStdout = true, returnStatus = true)
        }

        assertEquals(
            "Shell payload cannot enable both returnStdout and returnStatus",
            thrown.message,
            "the codec boundary and its callers rely on this message; changing it is a contract break",
        )
    }

    @Test
    fun `the three legal flag combinations map to the three modes`() {
        assertEquals(ShellReturnMode.NONE, decideShellReturnMode(returnStdout = false, returnStatus = false))
        assertEquals(ShellReturnMode.STDOUT, decideShellReturnMode(returnStdout = true, returnStatus = false))
        assertEquals(ShellReturnMode.STATUS, decideShellReturnMode(returnStdout = false, returnStatus = true))
    }
}
