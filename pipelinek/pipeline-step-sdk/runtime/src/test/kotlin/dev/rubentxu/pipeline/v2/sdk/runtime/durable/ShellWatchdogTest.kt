package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The watch's rules about time and termination are decisions over facts, and the decisions are
 * where this can be wrong without anybody noticing.
 *
 * **The guarantee.** An execution that ran out of time is never reported as a success, and the
 * evidence of the expiry is never erased. A durable `sh` step with a deadline that passes returns
 * a TIMEOUT terminal, not `Exited(0)`, even when the killed script's surviving shell published
 * `result.txt=0` right before the SIGKILL; and the cleanup that follows reads the expiry, not
 * that exit code, so the control directory — and with it the `timeout.flag` the reconciler reads
 * to tell TIMED_OUT from LOST — survives.
 *
 * **The observable capacity it serves.** A reader of a durable run can tell an execution that
 * expired from one that finished, and a run that expired can be reconciled afterwards instead of
 * looking lost. Before this, the whole of that rested on the inline logic of one executor method:
 * the only way to observe it was to launch a child that outlives its deadline and read the files
 * it left behind, and that instrument says THAT the timeout broke, never WHICH of its rules.
 *
 * **Why this instrument and not another.** The watch's imperative half — the timer thread, the
 * cookie-scan kill over `/proc`, the flag write — needs a real clock, a real child and a real
 * filesystem, none of which a unit test may use. But the decisions it applies are pure functions
 * of facts (a deadline, a latch, a flag, an exit code, a projection), so the cheapest instrument
 * that can fail is a call to each of them. A test that faked the timer would cost more, prove
 * less, and add a second clock to keep in sync with the real one.
 *
 * **What this does not freeze.** That the timer thread actually fires, that the kill reaches the
 * child's whole process tree, and that the flag is written before the kill — those are the
 * imperative half, and they are observed where they run, not here. What is checked here is the
 * verdict those observations feed, which is the part that can be wrong while every observation
 * succeeds.
 */
class ShellWatchdogTest {

    private val controlDir: Path = Path.of("/tmp/pipelinek-shell-watchdog/op-42")

    private fun watch(
        timeoutMs: Long = 1_000L,
        completion: DurableShellTimeoutCompletion = DurableShellTimeoutCompletion.WATCHDOG_ATTEMPT,
    ): ShellWatchdog = ShellWatchdog(
        controlDir = controlDir,
        opId = "op-42",
        timeoutMs = timeoutMs,
        completion = completion,
    )

    @Test
    fun `an execution without a deadline has no watch`() {
        assertFalse(
            ShellWatchdog.watchArmed(timeoutMs = 0L),
            "an execution with no deadline was given a watch",
        )
    }

    @Test
    fun `an execution with a deadline has a watch`() {
        assertTrue(
            ShellWatchdog.watchArmed(timeoutMs = 1L),
            "an execution with a deadline was left unwatched",
        )
    }

    @Test
    fun `the poll outlasts the kill`() {
        // The poll must not give up while the child is still being killed: the kill needs a grace
        // of its own, and a poll that ended first would report the execution as still running.
        assertEquals(
            1_000L + ShellWatchdog.POLL_GRACE_MS,
            ShellWatchdog.pollDeadlineFor(timeoutMs = 1_000L),
            "the poll does not outlast the kill",
        )
    }

    @Test
    fun `an execution without a deadline polls for an hour`() {
        assertEquals(
            ShellWatchdog.NO_TIMEOUT_POLL_MS,
            ShellWatchdog.pollDeadlineFor(timeoutMs = 0L),
            "an execution without a deadline does not poll for the no-timeout ceiling",
        )
    }

    @Test
    fun `the flag wins over the latch that has not been published`() {
        // The flag is written BEFORE the kill, so it exists in the window where the watch has
        // fired but has not yet published. An execution that expired must be reported as expired
        // in that window too.
        assertTrue(
            ShellWatchdog.runExpired(fired = false, flagPresent = true),
            "the expiry was lost because the watch had not published yet",
        )
    }

    @Test
    fun `an execution that did not expire did not expire`() {
        assertFalse(
            ShellWatchdog.runExpired(fired = false, flagPresent = false),
            "an execution that neither fired nor left a flag was reported as expired",
        )
    }

    @Test
    fun `a kill that found nothing is not a timeout`() {
        // Under KILL_RESULT the verdict follows the kill's own result: a scan that matched no
        // process means the deadline was not the reason the execution ended.
        assertFalse(
            ShellWatchdog.watchCompleted(
                killResult = false,
                completion = DurableShellTimeoutCompletion.KILL_RESULT,
            ),
            "a kill that found nothing was reported as a timeout",
        )
    }

    @Test
    fun `a kill that ran is a timeout`() {
        assertTrue(
            ShellWatchdog.watchCompleted(
                killResult = true,
                completion = DurableShellTimeoutCompletion.KILL_RESULT,
            ),
            "a kill that ran was not reported as a timeout",
        )
    }

    @Test
    fun `a failed kill is still a timeout when the verdict follows the attempt`() {
        // Under WATCHDOG_ATTEMPT the deadline itself is the fact: the attempt was made.
        assertTrue(
            ShellWatchdog.watchCompleted(
                killResult = false,
                completion = DurableShellTimeoutCompletion.WATCHDOG_ATTEMPT,
            ),
            "a failed kill was not reported as a timeout",
        )
    }

    @Test
    fun `an expired execution is a failure whatever the wrapper published`() {
        // The surviving wrapper can publish 0 after its child was SIGKILLed. A cleanup that reads
        // that 0 as a success deletes the control dir, and with it the authoritative flag.
        assertEquals(
            -1,
            ShellWatchdog.exitCodeForCleanup(timedOut = true, exitCode = 0),
            "the expired execution was cleaned up as a success",
        )
    }

    @Test
    fun `an execution that did not expire keeps the wrapper's exit code`() {
        assertEquals(
            7,
            ShellWatchdog.exitCodeForCleanup(timedOut = false, exitCode = 7),
            "an execution that did not expire lost its exit code",
        )
    }

    @Test
    fun `an expired execution keeps nothing`() {
        // The control directory is the evidence of the expiry, so an expired execution keeps no
        // transcript: nothing may be read as the run's output.
        assertFalse(
            ShellWatchdog.keepsTranscriptFor(
                timedOut = true,
                exitCode = 0,
                projection = DurableShellOutputProjection.JENKINS_LOG,
            ),
            "an expired execution kept a transcript",
        )
    }

    @Test
    fun `a successful plain projection keeps its transcript`() {
        // The production consumer streams console.log after the executor returns and deletes it
        // itself, so the executor must not.
        assertTrue(
            ShellWatchdog.keepsTranscriptFor(
                timedOut = false,
                exitCode = 0,
                projection = DurableShellOutputProjection.JENKINS_LOG,
            ),
            "a successful plain projection lost its transcript",
        )
    }

    @Test
    fun `a captured projection keeps no transcript`() {
        // In capture mode the transcript is not materialised for a reader: the typed value is the
        // stdout file, and the whole directory is deleted.
        assertFalse(
            ShellWatchdog.keepsTranscriptFor(
                timedOut = false,
                exitCode = 0,
                projection = DurableShellOutputProjection.CAPTURE_FILE,
            ),
            "a captured projection kept a transcript nobody reads",
        )
    }

    @Test
    fun `the terminal of an expired execution is a timeout`() {
        val terminal = watch().timeoutTerminal()

        assertEquals(
            InterruptionKind.TIMEOUT,
            terminal.interruption.kind,
            "the terminal of an expired execution is not a timeout",
        )
        assertEquals(
            "op-42",
            terminal.interruption.operationId,
            "the terminal of an expired execution does not name its operation",
        )
    }
}
