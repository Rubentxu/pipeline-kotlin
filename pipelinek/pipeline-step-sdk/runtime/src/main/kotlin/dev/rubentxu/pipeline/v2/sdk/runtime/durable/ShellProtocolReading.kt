package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.nio.file.Files

/**
 * The two facts the wait for a durable shell's result reads, and the order it reads them in.
 *
 * ## Why these two facts have an ORDER
 *
 * The wrapper publishes its exit code BEFORE it ends:
 * ```text
 * echo $EXIT_CODE > result.txt.tmp ; mv result.txt.tmp result.txt ; rm -f .cookie
 * ```
 * So a wrapper that is GONE had already published, and the result file is already there. A
 * reading of the result that PRECEDES the reading of the state leaves a window between the two:
 * if the wrapper publishes and ends inside that window, the first reading says "no result" and
 * the second says "the wrapper ended", and the wait concludes that the execution published
 * nothing.
 *
 * That conclusion is a LOSS. A loss is retryable, so a step that completed would run again and
 * its effect would happen twice — the very thing a durable execution exists to prevent. Reading
 * the state first and the result after closes the window: once the wrapper is observed to be
 * gone, the publication it had already made is visible to the reading that follows.
 *
 * ## What this owns
 *
 * The readings, and nothing else. WHEN the wait ends and WHAT it ends with belong to
 * [ShellResultPoll]; the rule that turns these readings into an outcome is [shellPollOutcome].
 */
internal interface ShellProtocolReading {

    /**
     * The wrapper's state, read now.
     *
     * @return what the control directory and the launch say about the wrapper at this instant.
     */
    fun state(): ShellAvailability

    /**
     * What the result file holds, read now.
     *
     * @return the reading of the result the wrapper may have published.
     */
    fun result(): ShellResultRead
}

/**
 * The reading of a launched wrapper's protocol: the control directory and the launch.
 *
 * ## Where the facts come from
 *
 * - the STATE: the cookie the wrapper publishes, and the child the launcher started — its handle
 *   for the liveness, and its reader for the code it left with. Both are read, and neither is
 *   inferred from the absence of the other. The reading is [shellAvailability]'s rule.
 * - the RESULT: the file the wrapper's `mv` publishes.
 *
 * ## Why a result that is not an exit code is ONE fact
 *
 * Whether the file is unreadable or holds something that is not a number, the protocol's promise
 * about that file is broken and the outcome cannot be established. This is the same rule
 * [StepReconcilerL1] applies when a LATER process reads the directory a dead worker left behind,
 * and the two must agree: the executor's verdict and the reconciler's verdict are read by
 * different processes, and a disagreement between them makes the run unreadable.
 */
internal class LaunchedShellProtocolReading(
    private val protocol: ShellProtocolLayout,
    private val launch: LaunchedShell,
) : ShellProtocolReading {

    override fun state(): ShellAvailability {
        val identityPublished = Files.exists(protocol.cookieFile)
        val running = launch.handle.isAlive
        val exitCode = if (running) null else launch.exitCode.read()
        return shellAvailability(identityPublished, !running, exitCode)
    }

    override fun result(): ShellResultRead {
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
