package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The rules that say what a durable shell execution publishes when it ends, and the precedence
 * between them, are decisions over facts, and the decisions are where this can be wrong without
 * anybody noticing.
 *
 * **The guarantee.** The terminal published for a durable shell execution names what actually
 * happened: an execution that ran out of time is a TIMEOUT whatever exit code its dying child
 * published, a failure before the launch is a LAUNCH FAILURE because nothing ran, a failure that
 * cannot be reconciled is LOST and never a success, and an execution whose output could not be
 * persisted says so instead of publishing a plausible transcript. The terminal agrees with the
 * reconciler, which reads the same mark of the expiry from the control directory.
 *
 * **The observable capacity it serves.** A reader of a durable run can tell an execution that
 * expired from one that failed from one that was lost, and a run that ended in any of those three
 * can be reconciled afterwards instead of looking like a success. Before this, the whole of that
 * rested on inline branches inside one executor method: the only way to observe it was to launch a
 * child and make it fail in the right way, and that instrument says THAT the classification broke,
 * never WHICH of its rules broke.
 *
 * **Why this instrument and not another.** The classification is a pure function of facts — an
 * exit code, a mark of the expiry, whether the child was launched, a refusal — so the cheapest
 * instrument that can fail is a call to it. A test that launched a real child to make it expire or
 * to fail before the launch would cost more, prove less, and add a process and a clock that a unit
 * test may not use. The facts that only appear when a child really runs — whether the exit code
 * reaches the terminal, whether the reconciler agrees — stay covered by the scenarios.
 *
 * **What this does not freeze.** That the executor gathers the right facts, that the reconciler
 * reads the same mark, and that a child that expires really leaves the mark behind: those are
 * observed where they happen, not here. What is checked here is the verdict those facts feed,
 * which is the part that can be wrong while every observation succeeds.
 */
class ShellTerminalDecisionTest {

    private val controlDir: Path = Path.of("/tmp/pipelinek-shell-terminal-decision/op-42")

    private fun decision(opId: String = "op-42"): ShellTerminalDecision =
        ShellTerminalDecision(opId = opId, controlDir = controlDir)

    @Test
    fun `an execution that published an exit code is an exit`() {
        val terminal = decision().forPollOutcome(
            outcome = ShellPollOutcome.Published(exitCode = 0),
            capturedStdout = null,
        )

        val exited = assertInstanceOf(
            DurableTaskTerminal.Exited::class.java,
            terminal,
            "an execution that published an exit code is not an exit",
        )
        assertEquals(0, exited.exitCode, "the exit code did not reach the terminal")
        assertEquals(
            controlDir.toString(),
            exited.output.controlDir,
            "the terminal does not point at the control directory",
        )
    }

    @Test
    fun `a non-zero exit code is still an exit`() {
        // The exit code is the child's own verdict. A step that failed on its own terms is an exit
        // with a non-zero code, not a failure of the launch: the child DID run.
        val terminal = decision().forPollOutcome(
            outcome = ShellPollOutcome.Published(exitCode = 7),
            capturedStdout = null,
        )

        val exited = assertInstanceOf(
            DurableTaskTerminal.Exited::class.java,
            terminal,
            "a non-zero exit code was not published as an exit",
        )
        assertEquals(7, exited.exitCode, "the non-zero exit code did not reach the terminal")
    }

    @Test
    fun `the captured value reaches the exit terminal`() {
        val terminal = decision().forPollOutcome(
            outcome = ShellPollOutcome.Published(exitCode = 0),
            capturedStdout = "hello",
        )

        val exited = assertInstanceOf(
            DurableTaskTerminal.Exited::class.java,
            terminal,
            "an execution with a captured value is not an exit",
        )
        assertEquals(
            "hello",
            exited.output.capturedStdout,
            "the captured value did not reach the terminal",
        )
    }

    @Test
    fun `a wrapper that ended without publishing a result is lost`() {
        // There is no exit code to publish: the wrapper never wrote one. The terminal says the
        // outcome could not be established, and it does NOT carry an exit code that nobody
        // observed.
        val terminal = decision().forPollOutcome(
            outcome = ShellPollOutcome.WrapperEnded,
            capturedStdout = null,
        )

        val lost = assertInstanceOf(
            DurableTaskTerminal.Lost::class.java,
            terminal,
            "an execution that published no result was not published as a loss",
        )
        assertTrue(
            lost.failure.retryable,
            "a lost execution is not retryable, and a retry is the caller's answer to it",
        )
    }

    @Test
    fun `a result that is not an exit code is lost`() {
        // The protocol's promise about the result file is broken, so the outcome cannot be
        // established. Reading another protocol's record as this one's success is the
        // fabrication the reconciler refuses too.
        val terminal = decision().forPollOutcome(
            outcome = ShellPollOutcome.ResultNotAnExitCode,
            capturedStdout = null,
        )

        assertInstanceOf(
            DurableTaskTerminal.Lost::class.java,
            terminal,
            "a result that is not an exit code was not published as a loss",
        )
    }

    @Test
    fun `a ceiling that elapsed with nothing published is lost`() {
        // The wait ended and the execution published nothing. The outcome cannot be established,
        // so it is a loss and not an exit with a made-up code.
        val terminal = decision().forPollOutcome(
            outcome = ShellPollOutcome.CeilingElapsed,
            capturedStdout = null,
        )

        assertInstanceOf(
            DurableTaskTerminal.Lost::class.java,
            terminal,
            "an execution that published nothing was not published as a loss",
        )
    }

    @Test
    fun `an expired execution is a timeout whatever the exit code says`() {
        // The child that published the exit code was killed. A wrapper that keeps writing after
        // its child died does not get to decide whether the execution succeeded.
        val terminal = decision().forPollOutcome(
            outcome = ShellPollOutcome.Expired,
            capturedStdout = null,
        )

        val cancelled = assertInstanceOf(
            DurableTaskTerminal.Cancelled::class.java,
            terminal,
            "an expired execution was published as something other than a timeout",
        )
        assertEquals(
            InterruptionKind.TIMEOUT,
            cancelled.interruption.kind,
            "the interruption of an expired execution is not a timeout",
        )
        assertEquals(
            "op-42",
            cancelled.interruption.operationId,
            "the interruption of an expired execution does not name its operation",
        )
        assertEquals(
            "durable shell timed out",
            cancelled.interruption.message,
            "the interruption of an expired execution does not say that a shell expired",
        )
        assertEquals(
            controlDir.toString(),
            cancelled.interruption.details["controlDir"],
            "the interruption of an expired execution does not point at the control directory",
        )
    }

    @Test
    fun `a failure before the launch is a launch failure`() {
        val terminal = decision().forFailure(
            launched = false,
            timedOut = false,
            cause = IllegalStateException("could not fork"),
        )

        val failed = assertInstanceOf(
            DurableTaskTerminal.LaunchFailed::class.java,
            terminal,
            "a failure before the launch was not published as a launch failure",
        )
        assertEquals(
            "DURABLE_LAUNCH_FAILED",
            failed.failure.code,
            "the launch failure does not carry the launch failure code",
        )
        assertEquals(
            FailureOrigin.LAUNCHER,
            failed.failure.origin,
            "the launch failure does not come from the launcher",
        )
        assertEquals(
            FailureKind.INFRASTRUCTURE,
            failed.failure.kind,
            "the launch failure is not an infrastructure failure",
        )
        assertFalse(
            failed.failure.retryable,
            "a failure before the launch was published as retryable",
        )
        assertEquals(
            "op-42",
            failed.failure.operationId,
            "the launch failure does not name its operation",
        )
    }

    @Test
    fun `a failure after the launch is lost`() {
        val terminal = decision().forFailure(
            launched = true,
            timedOut = false,
            cause = IllegalStateException("could not be reconciled"),
        )

        val lost = assertInstanceOf(
            DurableTaskTerminal.Lost::class.java,
            terminal,
            "a failure that cannot be reconciled was not published as lost",
        )
        assertEquals(
            "DURABLE_TASK_LOST",
            lost.failure.code,
            "the lost terminal does not carry the lost code",
        )
        assertEquals(
            FailureOrigin.RECONCILIATION,
            lost.failure.origin,
            "the lost terminal does not come from the reconciliation",
        )
        assertTrue(
            lost.failure.retryable,
            "a lost task was published as not retryable",
        )
        assertEquals(
            controlDir.toString(),
            lost.failure.details["controlDir"],
            "the lost terminal does not point at the control directory",
        )
    }

    @Test
    fun `a failure after an expiry is the expiry`() {
        // The reconciler reads the mark of the expiry from the control directory. A terminal that
        // said "lost" here would contradict it.
        val terminal = decision().forFailure(
            launched = true,
            timedOut = true,
            cause = IllegalStateException("the wrapper died on the way out"),
        )

        val cancelled = assertInstanceOf(
            DurableTaskTerminal.Cancelled::class.java,
            terminal,
            "a failure after an expiry was not published as the expiry",
        )
        assertEquals(
            InterruptionKind.TIMEOUT,
            cancelled.interruption.kind,
            "the interruption of an expired execution is not a timeout",
        )
    }

    @Test
    fun `a failure before the launch stays a launch failure even if it expired`() {
        // Nothing ran, so there is nothing to time out: the precedence of the two facts is the
        // rule, and the order of the branches is not incidental.
        val terminal = decision().forFailure(
            launched = false,
            timedOut = true,
            cause = IllegalStateException("could not fork"),
        )

        assertInstanceOf(
            DurableTaskTerminal.LaunchFailed::class.java,
            terminal,
            "a failure before the launch was published as something other than a launch failure",
        )
    }

    @Test
    fun `a failure that says nothing says it could not be reconciled`() {
        val terminal = decision().forFailure(
            launched = true,
            timedOut = false,
            cause = Exception(),
        )

        val lost = assertInstanceOf(
            DurableTaskTerminal.Lost::class.java,
            terminal,
            "a failure that says nothing was not published as lost",
        )
        assertEquals(
            "durable task could not be reconciled",
            lost.failure.message,
            "the lost terminal does not say what could not be reconciled",
        )
    }

    @Test
    fun `a refusal of the output says the output could not be persisted`() {
        val terminal = decision().forOutputRefusal(
            ProcessOutputRefusal.WriteFailed(
                detail = "the sink refused the bytes",
                cause = IllegalStateException("the store is closed"),
            ),
        )

        val failed = assertInstanceOf(
            DurableTaskTerminal.LaunchFailed::class.java,
            terminal,
            "a refusal of the output was not published as a step failure",
        )
        assertEquals(
            OUTPUT_PERSISTENCE_REFUSED,
            failed.failure.code,
            "the refusal does not carry the output refusal code",
        )
        assertEquals(
            FailureKind.INFRASTRUCTURE,
            failed.failure.kind,
            "the refusal is not an infrastructure failure",
        )
        assertEquals(
            FailureOrigin.DURABLE_TASK,
            failed.failure.origin,
            "the refusal does not come from the durable task",
        )
        assertFalse(
            failed.failure.retryable,
            "a refusal of the output was published as retryable",
        )
        assertTrue(
            failed.failure.message.contains("the sink refused the bytes"),
            "the refusal does not say what the sink refused: ${failed.failure.message}",
        )
    }

    @Test
    fun `a write that failed names the failure that wrote`() {
        val terminal = decision().forOutputRefusal(
            ProcessOutputRefusal.WriteFailed(
                detail = "the sink refused the bytes",
                cause = IllegalStateException("the store is closed"),
            ),
        )

        val failed = assertInstanceOf(
            DurableTaskTerminal.LaunchFailed::class.java,
            terminal,
            "a refusal of the output was not published as a step failure",
        )
        assertEquals(
            "IllegalStateException",
            failed.failure.details["cause"],
            "the refusal does not name the failure that wrote",
        )
    }

    @Test
    fun `a stuck child is not a drain that ran out of time`() {
        val terminal = decision().forOutputRefusal(
            ProcessOutputRefusal.DrainIncomplete(
                detail = "the pump did not finish",
                waitedMs = 500L,
                cause = TranscriptDrainCause.STUCK_CHILD,
            ),
        )

        val failed = assertInstanceOf(
            DurableTaskTerminal.LaunchFailed::class.java,
            terminal,
            "a refusal of the output was not published as a step failure",
        )
        assertEquals(
            "transcript-pump-still-alive",
            failed.failure.details["cause"],
            "a stuck child was not named as a stuck child",
        )
    }

    @Test
    fun `a drain that ran out of time is not a stuck child`() {
        val terminal = decision().forOutputRefusal(
            ProcessOutputRefusal.DrainIncomplete(
                detail = "the pump did not finish",
                waitedMs = 500L,
                cause = TranscriptDrainCause.OUT_OF_TIME,
            ),
        )

        val failed = assertInstanceOf(
            DurableTaskTerminal.LaunchFailed::class.java,
            terminal,
            "a refusal of the output was not published as a step failure",
        )
        assertEquals(
            "transcript-drain-out-of-time",
            failed.failure.details["cause"],
            "a drain that ran out of time was not named as a drain that ran out of time",
        )
    }
}
