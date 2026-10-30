package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.nio.file.Files
import java.nio.file.Path

/**
 * The poll over one durable shell's result: WHEN the wait ends, and WHAT it ends with.
 *
 * ## What this owns
 *
 * The WAIT and its ending. The poll reads the control directory until one of the protocol's facts
 * says the execution is over, and it names WHICH fact it was. Nothing else: the terminal that the
 * outcome becomes belongs to [ShellTerminalDecision], and the deadline that may bound the wait
 * belongs to [ShellWatchdog].
 *
 * ## The facts that end a wait
 *
 * - the wrapper PUBLISHED a result: `result.txt` holds an exit code;
 * - the wrapper ENDED without publishing one: the wrapper's process is gone and no result was
 *   published;
 * - the execution EXPIRED: the watch published it, or the `timeout.flag` it writes before the
 *   kill exists;
 * - the CEILING elapsed: the deadline and the grace the kill needs are over, and nothing was
 *   published.
 *
 * ## Why "the wrapper ended" is a fact and not a guess
 *
 * The wrapper publishes the result BEFORE it removes the cookie:
 * ```text
 * echo $EXIT_CODE > result.txt.tmp ; mv result.txt.tmp result.txt ; rm -f .cookie
 * ```
 * So a wrapper that is gone with no result published never reached the publication: it was
 * killed, or it died. Either way the exit code does not exist, and the only honest terminal for
 * an exit code that does not exist is the one that says the outcome could not be established. It
 * is NOT an exit code of -1: -1 is a number the wrapper never wrote, and publishing it as if it
 * had been observed is the fabrication this class exists to prevent.
 *
 * The wrapper's own exit code, when the platform reported one, travels with that ending: the
 * process that failed is the one that knows why it failed.
 *
 * ## Where the wrapper's state comes from
 *
 * From the two facts the launch holds, and from nothing else: the cookie the wrapper publishes,
 * and the child the launcher started — its handle for the liveness, and its reader for the code
 * it left with. The reading is [ShellAvailability]'s rule, and the wait never infers the
 * wrapper's state from the absence of a file.
 *
 * ## Why an execution with no deadline has no ceiling
 *
 * An execution asked not to time out is not abandoned by a clock the operator never set. There was
 * one here: an hour. It made "no timeout" mean "one hour, and then a made-up exit code", and the
 * test that pinned it was green — a green that protected the wrong rule. The ceiling now comes
 * from the deadline alone, and an execution with no deadline has none: the wait ends when the
 * protocol says it ends.
 */
internal class ShellResultPoll(
    controlDir: Path,
    private val pollIntervalMs: Long,
) {

    /** The names of the protocol. The result and the cookie are two of them. */
    private val protocol = ShellProtocolLayout(controlDir)

    /**
     * Waits for the execution's result.
     *
     * @param ceilingNanos the instant the wait gives up, on the MONOTONIC clock, or null when the
     *   execution has no deadline and therefore no ceiling.
     * @param launch the launch that started the wrapper. The poll cannot do without it: the
     *   wrapper's liveness and the code it left with are two of the facts it reads, and a wait
     *   that cannot see the wrapper cannot say that the wrapper ended.
     * @param expired the authority on "this execution expired", asked once per iteration: the
     *   poll must not keep waiting on an execution that already expired, and the authority is the
     *   only thing that can say so.
     * @return what ended the wait.
     */
    fun await(
        ceilingNanos: Long?,
        launch: LaunchedShell,
        expired: () -> Boolean,
    ): ShellPollOutcome {
        while (true) {
            val outcome = shellPollOutcome(
                result = readResult(),
                availability = availability(launch),
                expired = expired(),
                ceilingReached = ceilingNanos != null && System.nanoTime() >= ceilingNanos,
            )
            if (outcome !is ShellPollOutcome.KeepWaiting) {
                return outcome
            }
            Thread.sleep(pollIntervalMs)
        }
    }

    /**
     * The wrapper's state, read from the two facts the launch holds.
     *
     * The cookie says the wrapper published itself, and the launch says whether the wrapper is
     * still there and what code it left with. Both are read, and neither is inferred from the
     * absence of the other.
     */
    private fun availability(launch: LaunchedShell): ShellAvailability {
        val identityPublished = Files.exists(protocol.cookieFile)
        val running = launch.handle.isAlive
        val exitCode = if (running) null else launch.exitCode.read()
        return shellAvailability(identityPublished, !running, exitCode)
    }

    /**
     * What the control directory's result file holds.
     *
     * A result that exists and does not hold an exit code is ONE fact, not two: whether it is
     * unreadable or malformed, the protocol's promise about that file is broken, and the outcome
     * cannot be established. This is the same rule [StepReconcilerL1] applies when a LATER
     * process reads the directory a dead worker left behind, and the two must agree: the
     * executor's verdict and the reconciler's verdict are read by different processes and a
     * disagreement between them makes the run unreadable.
     */
    private fun readResult(): ShellResultRead {
        val resultFile = protocol.resultFile
        if (!Files.exists(resultFile)) {
            return ShellResultRead.Absent
        }
        val text = try {
            Files.readString(resultFile).trim()
        } catch (_: Exception) {
            return ShellResultRead.NotAnExitCode
        }
        val code = text.toIntOrNull() ?: return ShellResultRead.NotAnExitCode
        return ShellResultRead.Code(code)
    }
}

/**
 * What the control directory's result file holds.
 */
internal sealed interface ShellResultRead {

    /** No result file: the wrapper has not published anything yet. */
    data object Absent : ShellResultRead

    /** The result file exists and does not hold an exit code. */
    data object NotAnExitCode : ShellResultRead

    /** The exit code the wrapper published. */
    data class Code(val exitCode: Int) : ShellResultRead
}

/**
 * What ended the wait for a durable shell's result.
 */
internal sealed interface ShellPollOutcome {

    /** The wrapper published an exit code. */
    data class Published(val exitCode: Int) : ShellPollOutcome

    /** The execution expired: the watch fired, or it left the mark of the expiry. */
    data object Expired : ShellPollOutcome

    /** The result file exists and does not hold an exit code. */
    data object ResultNotAnExitCode : ShellPollOutcome

    /**
     * The wrapper ended without publishing a result.
     *
     * @param exitCode the code the wrapper exited with, or null when the platform did not report
     *   one. It is the wrapper's own reason for failing, and it is carried rather than
     *   reconstructed.
     */
    data class WrapperEnded(val exitCode: Int?) : ShellPollOutcome

    /** The ceiling elapsed with nothing published. */
    data object CeilingElapsed : ShellPollOutcome

    /** Nothing is known yet: keep waiting. */
    data object KeepWaiting : ShellPollOutcome
}

/**
 * What the control directory's facts imply about the wait.
 *
 * This is the whole rule, and it is a pure function of facts: it reads nothing itself, and the
 * same facts always produce the same outcome. That is what lets the guarantee — an execution that
 * publishes no result is never reported as an exit code — be observed without a child on disk.
 *
 * ## The order of the branches, and why it is this order
 *
 * 1. the expiry first: a `timeout.flag` exists, so the execution expired whatever else the
 *    directory holds, and a result published by a child that was killed mid-publication is not a
 *    legitimate completion;
 * 2. the published result next: it is the strongest fact available;
 * 3. a result that is not an exit code next: the protocol's promise is broken and the outcome
 *    cannot be established;
 * 4. the wrapper's ending next: it is gone and published nothing, so there is no exit code, and
 *    the code it left with is the reason;
 * 5. the ceiling last: it says the wait is over, not what the execution did.
 *
 * A wrapper that is RUNNING — with or without its cookie — keeps the wait open. The cookie's
 * absence is not the wrapper's absence: a wrapper that had not published its identity yet is
 * starting, and reading that as an ending reported a wrapper that was on its way as one that
 * never arrived.
 */
internal fun shellPollOutcome(
    result: ShellResultRead,
    availability: ShellAvailability,
    expired: Boolean,
    ceilingReached: Boolean,
): ShellPollOutcome = when {
    expired -> ShellPollOutcome.Expired
    result is ShellResultRead.Code -> ShellPollOutcome.Published(result.exitCode)
    result is ShellResultRead.NotAnExitCode -> ShellPollOutcome.ResultNotAnExitCode
    availability is ShellAvailability.Ended -> ShellPollOutcome.WrapperEnded(availability.exitCode)
    ceilingReached -> ShellPollOutcome.CeilingElapsed
    else -> ShellPollOutcome.KeepWaiting
}
