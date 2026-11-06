package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import java.nio.file.Path

/**
 * The classification of a durable shell execution's ending into the terminal it publishes.
 *
 * ## What this owns
 *
 * The VERDICT: given what is known about how an execution ended, which of the four terminal shapes
 * the durable protocol publishes — `Exited`, `LaunchFailed`, `Lost`, `Cancelled` — and what that
 * terminal says. Nothing else. The facts come from elsewhere: the deadline and the mark of an
 * expiry are [ShellWatchdog]'s, the bytes of the child are the launch's, and the release of the
 * resources is the cleanup's.
 *
 * ## Why the rules live here and not in the executor
 *
 * The executor used to decide inline, at three separate places, and each place decided a
 * DIFFERENT question:
 *
 *  - at the end of the normal path: "did it exit, or did it expire?" — with the exit code of a
 *    child that was killed in the middle of publishing it;
 *  - at the output refusal: "the bytes are gone, so what does the step say?";
 *  - in the catch: "did it fail before the launch, after an expiry, or in a way nobody can
 *    reconcile?".
 *
 * Three places, three rules, and no instrument that could observe any of them: the only way to see
 * them was to launch a child and make it fail in the right way, and that says THAT the rule broke,
 * never WHICH of them broke. Here the rules are pure functions of facts, and a fact that is not
 * known cannot be invented: the executor gathers them and asks.
 *
 * ## This is NOT the domain's `TaskTerminalDecision`
 *
 * The domain has a `TaskTerminalDecision` that projects a `TaskExecutionResult` into three plain
 * shapes —`Exited`, `TimedOut`, `Cancelled`— for the PROCESS TASK runtime, which is a different
 * protocol with a different control directory and a different content in its `result.txt`. This one
 * classifies a durable SHELL execution into the four rich shapes of `DurableTaskTerminal`, which
 * carry a failure record, an interruption record and an output. Two concepts, two protocols, two
 * sets of shapes; the names are close and the subjects are not.
 *
 * ## What this does NOT own
 *
 * The RECONCILER builds its terminal from what the control directory holds —that is a
 * reconstruction of an execution nobody watched, and its interruption says "durable task timed
 * out", not "durable shell timed out"— so it keeps building its own.
 *
 * ## Why this is visible outside this module
 *
 * There are TWO substrates that run a shell step: the durable-shell one, which needs a Linux host,
 * and the generic process one, which is what runs on the rest. The generic one publishes its
 * terminal too, and that terminal is read by the same consumers — the domain's projection of a
 * terminal into a shell value. If each substrate classified its own ending, the two could
 * disagree about the very facts a consumer reads: an absent value, an empty value, a value that
 * could not be read.
 *
 * So the CLASSIFICATION is shared, and this class is its home. It is not a public API of the
 * product: the module is not published and carries no ABI dump, so nothing an external consumer
 * compiles against changes.
 */
class ShellTerminalDecision(
    private val opId: String,
    private val controlDir: Path,
) {

    /**
     * The terminal of an execution that ENDED with an exit code.
     *
     * It is the entry the second substrate uses: a substrate whose protocol ends with an exit code
     * — rather than with a wrapper that publishes one — asks this class what that exit code means,
     * and gets the same terminal the durable substrate would publish for the same facts. The
     * captured value is consulted the same way too: an absent channel, an empty one, a value that
     * could not be read, and a value that was read but not disposed of are the same four facts
     * here as they are there.
     *
     * @param exitCode The code the execution ended with.
     * @param captured What is known about the captured stdout.
     * @return The terminal this execution publishes.
     */
    fun forExit(exitCode: Int, captured: CapturedStdoutRead): DurableTaskTerminal =
        forPollOutcome(ShellPollOutcome.Published(exitCode), captured)

    /**
     * The terminal of an execution whose wait for the result ended.
     *
     * The outcome is what ended the wait, and the terminal is what that outcome means. A published
     * exit code is an EXIT. An expiry is a TIMEOUT. Everything else — a result that is not an exit
     * code, a wrapper that ended without publishing one, a ceiling that elapsed with nothing
     * published — is LOST, because the outcome could not be established, and LOST never implies
     * success.
     *
     * The expiry is folded into the outcome by the caller, so there is ONE authority for it here:
     * the caller's final reading of the mark of the expiry, which the reconciler reads from the
     * same control directory. A terminal that disagreed with the reconciler would make the run
     * unreadable. That is also why the expiry is decided BEFORE the captured value is read: a
     * capture that could not be read does not turn an expired execution into something else.
     *
     * The captured value is a FACT about the channel, and it is consulted only for an execution
     * that published an exit code. The other outcomes publish no value, and a value nobody could
     * establish is not published as one.
     */
    internal fun forPollOutcome(
        outcome: ShellPollOutcome,
        captured: CapturedStdoutRead,
    ): DurableTaskTerminal = when (outcome) {
        is ShellPollOutcome.Published -> when (captured) {
            // Nobody recorded anything: the execution has no value, and that is not a value.
            is CapturedStdoutRead.Absent -> exited(outcome.exitCode, capturedStdout = null)

            // The program printed this. The text may be empty, and an empty text is a value.
            is CapturedStdoutRead.Present -> exited(outcome.exitCode, capturedStdout = captured.text)

            // The channel is there and could not be read: there is no value, and an empty one
            // would be an invention.
            is CapturedStdoutRead.Unreadable -> captureRefused(captured.reason)

            // The value was read and the channel could not be disposed of: the value is known, and
            // the protocol's promise that the channel is gone is broken.
            is CapturedStdoutRead.NotDisposed -> captureNotDisposed(captured)
        }

        ShellPollOutcome.Expired -> expired()

        ShellPollOutcome.ResultNotAnExitCode -> lost(RESULT_NOT_AN_EXIT_CODE)

        is ShellPollOutcome.WrapperEnded -> lost(wrapperEndedMessage(outcome.exitCode))

        ShellPollOutcome.CeilingElapsed -> lost(CEILING_ELAPSED)

        // The poll only hands this over when it was interrupted, and an interrupted poll knows
        // nothing: the outcome cannot be established, so it is LOST.
        ShellPollOutcome.KeepWaiting -> lost(NO_FACT)
    }

    /**
     * The terminal of an execution that published an exit code and a captured value.
     *
     * The value travels in the output, and the control directory travels with it: a reader that
     * wants the file the value came from finds it from the terminal itself.
     */
    private fun exited(exitCode: Int, capturedStdout: String?): DurableTaskTerminal.Exited =
        DurableTaskTerminal.Exited(
            exitCode = exitCode,
            output = DurableTaskOutput(
                controlDir = controlDir.toString(),
                capturedStdout = capturedStdout,
            ),
        )

    /**
     * The terminal of an execution whose captured value could not be read.
     *
     * The channel is there and the filesystem refused to serve it, so there is no value. The step
     * says so instead of publishing an empty one, because an empty value is the answer to "the
     * program printed nothing" and nobody knows that here.
     *
     * It is not retryable, for the same reason the refusal of the transcript is not: the bytes
     * left the pipe when the child wrote them, so the only way to obtain the value is to run the
     * child again, and whether to do that is the step's call, not this seam's.
     */
    private fun captureRefused(reason: String): DurableTaskTerminal.LaunchFailed =
        DurableTaskTerminal.LaunchFailed(
            FailureRecord(
                code = CAPTURE_READ_REFUSED,
                kind = FailureKind.INFRASTRUCTURE,
                message = "the captured stdout could not be read ($reason)",
                origin = FailureOrigin.DURABLE_TASK,
                retryable = false,
                operationId = opId,
                details = mapOf(
                    "cause" to reason,
                    "controlDir" to controlDir.toString(),
                ),
            ),
        )

    /**
     * The terminal of an execution whose captured value was read but not disposed of.
     *
     * The value was read, so the reading is not in doubt; what broke is the protocol's promise
     * that the channel is gone once it has been read. The details name the control directory, so
     * the operator can go and find the channel that is still there.
     *
     * It is not retryable: the value is known, so there is nothing for a retry to produce, and
     * re-running the child would only replace a known value with another one.
     */
    private fun captureNotDisposed(captured: CapturedStdoutRead.NotDisposed): DurableTaskTerminal.LaunchFailed =
        DurableTaskTerminal.LaunchFailed(
            FailureRecord(
                code = CAPTURE_DISPOSAL_REFUSED,
                kind = FailureKind.INFRASTRUCTURE,
                message = "the captured stdout could not be disposed of (${captured.reason})",
                origin = FailureOrigin.DURABLE_TASK,
                retryable = false,
                operationId = opId,
                details = mapOf(
                    "cause" to captured.reason,
                    "controlDir" to controlDir.toString(),
                ),
            ),
        )

    /**
     * The terminal of an execution whose outcome could not be established.
     *
     * LOST is retryable: the step did not publish a fact, so a new attempt with the same
     * fingerprint is the caller's answer, and saying so is what makes this terminal different
     * from a launch failure.
     */
    private fun lost(message: String): DurableTaskTerminal.Lost =
        DurableTaskTerminal.Lost(lostFailureRecord(opId, controlDir, message))

    /**
     * The reason a wrapper that ended without publishing gives.
     *
     * The wrapper's own exit code is the reason, because the process that failed is the one that
     * knows why it failed: a terminal that said only "the wrapper ended" left the reader to guess
     * between a script that could not be found and a host that refused to run it. A code the
     * platform did not report is not invented, and the reason then says only what is known.
     */
    private fun wrapperEndedMessage(exitCode: Int?): String = when (exitCode) {
        null -> WRAPPER_ENDED_WITHOUT_RESULT
        else -> "$WRAPPER_ENDED_WITH_EXIT_CODE $exitCode $WITHOUT_PUBLISHING_A_RESULT"
    }

    /**
     * The terminal of an execution that failed.
     *
     * The precedence IS the rule, and the order of the branches is not incidental:
     *
     *  - a failure BEFORE the launch is a launch failure: nothing ran, so there is nothing to
     *    reconcile and nothing to retry;
     *  - a failure AFTER an expiry is the expiry: the reconciler reads the mark of the expiry from
     *    the control directory, and a terminal that said "lost" would contradict it;
     *  - any other failure is LOST: the outcome cannot be established, and LOST never implies
     *    success.
     */
    fun forFailure(
        launched: Boolean,
        timedOut: Boolean,
        cause: Exception,
    ): DurableTaskTerminal = when {
        !launched -> DurableTaskTerminal.LaunchFailed(cause.launchFailureRecord(opId, controlDir))
        timedOut -> expired()
        else -> DurableTaskTerminal.Lost(
            lostFailureRecord(opId, controlDir, cause.message ?: LOST_MESSAGE),
        )
    }

    /**
     * The terminal of an execution that asked for two modes that cannot both hold the stdout.
     *
     * The capture wants the EXACT bytes as a typed value; the ingest agent wants the SANITIZED
     * bytes as the durable transcript, and it owns the child's stdout when it is the one pumping.
     * There is no stdout that is both, so the request is refused BEFORE the child exists:
     * nothing runs, nothing is written, and no value is invented for a program that never ran.
     *
     * The diagnostic is SAFE. It names the two modes and the directory where the control would
     * have happened, and it carries neither the script nor the agent's secret: a refusal is read
     * by whoever has to fix the request, and a refusal that leaked the request's own content
     * would be a second copy of it.
     *
     * The kind is the substrate's, like the other capture refusals: this substrate cannot serve
     * the combination, and the reason is not the user's script failing.
     *
     * It is not retryable: the refusal is about the REQUEST, and the same request would be
     * refused the same way.
     */
    fun forInadmissibleCapture(): DurableTaskTerminal.LaunchFailed =
        DurableTaskTerminal.LaunchFailed(
            FailureRecord(
                code = CAPTURE_AGENT_MODE_REFUSED,
                kind = FailureKind.INFRASTRUCTURE,
                message = "the captured stdout cannot be combined with the ingest agent: " +
                    "the agent owns the child's stdout, so the typed value is never written",
                origin = FailureOrigin.DURABLE_TASK,
                retryable = false,
                operationId = opId,
                details = mapOf(
                    "cause" to "the ingest agent owns the child's stdout, so the capture has no value",
                    "controlDir" to controlDir.toString(),
                ),
            ),
        )

    /**
     * The terminal of an execution whose output could not be persisted.
     *
     * The bytes left the pipe when the sink was handed them, so nothing can recover them: the
     * step says that its transcript is incomplete rather than publishing a short one that looks
     * plausible. The cause names WHICH of the two things went wrong, because the two call for
     * different answers: a stuck child needs a child that lets go of the transcript, and a drain
     * that ran out of time needs a machine that keeps up.
     */
    fun forOutputRefusal(refusal: ProcessOutputRefusal): DurableTaskTerminal =
        DurableTaskTerminal.LaunchFailed(
            FailureRecord(
                code = OUTPUT_PERSISTENCE_REFUSED,
                kind = FailureKind.INFRASTRUCTURE,
                message = "process output could not be persisted: ${refusal.detail}",
                origin = FailureOrigin.DURABLE_TASK,
                // Not retryable: the bytes are gone from the pipe, so a retry would have to
                // re-run the child to produce a transcript that is complete rather than
                // short. That is the step's call, not this seam's.
                retryable = false,
                operationId = opId,
                details = mapOf("cause" to refusalCause(refusal)),
            ),
        )

    /**
     * The terminal of an execution that ran out of time.
     *
     * The interruption names the operation, so a reader can tell WHICH execution expired among
     * the ones of the same run, and it points at the control directory, which is where the mark of
     * the expiry lives.
     */
    private fun expired(): DurableTaskTerminal.Cancelled = DurableTaskTerminal.Cancelled(
        InterruptionRecord(
            kind = InterruptionKind.TIMEOUT,
            message = "durable shell timed out",
            operationId = opId,
            details = mapOf("controlDir" to controlDir.toString()),
        ),
    )

    /**
     * The token that says WHICH of the two things went wrong.
     *
     * A write that failed names the failure that wrote; a drain that did not complete names the
     * thing that did not give way. They are different answers and they get different tokens.
     */
    private fun refusalCause(refusal: ProcessOutputRefusal): String = when (refusal) {
        is ProcessOutputRefusal.WriteFailed -> refusal.cause.javaClass.simpleName
        is ProcessOutputRefusal.DrainIncomplete -> when (refusal.cause) {
            TranscriptDrainCause.STUCK_CHILD -> STUCK_CHILD_CAUSE
            TranscriptDrainCause.OUT_OF_TIME -> OUT_OF_TIME_CAUSE
        }
    }

    private companion object {
        /** What a failure says when it says nothing itself. */
        const val LOST_MESSAGE = "durable task could not be reconciled"

        /** The result file exists and does not hold an exit code. */
        const val RESULT_NOT_AN_EXIT_CODE =
            "durable shell published a result that is not an exit code"

        /** The wrapper ended without publishing a result. */
        const val WRAPPER_ENDED_WITHOUT_RESULT =
            "durable shell wrapper ended without publishing a result"

        /** The wrapper ended with a code the platform reported. */
        const val WRAPPER_ENDED_WITH_EXIT_CODE = "durable shell wrapper ended with exit code"

        /** The part of the reason that says the result was never published. */
        const val WITHOUT_PUBLISHING_A_RESULT = "without publishing a result"

        /** The deadline elapsed with nothing published. */
        const val CEILING_ELAPSED =
            "durable shell did not publish a result before its deadline elapsed"

        /** The poll was interrupted before any fact was known. */
        const val NO_FACT = "durable shell poll ended without a fact"

        /** The token of a child that is holding the transcript open. */
        const val STUCK_CHILD_CAUSE = "transcript-pump-still-alive"

        /** The token of a machine that did not keep up. */
        const val OUT_OF_TIME_CAUSE = "transcript-drain-out-of-time"

        /**
         * The code of a capture that could not be read.
         *
         * It is not the code of the refusal of the transcript: the transcript was complete, and
         * the reader that decides whether the output may be sealed reads that other code.
         */
        const val CAPTURE_READ_REFUSED = "CAPTURE_READ_REFUSED"

        /** The code of a capture that was read and could not be disposed of. */
        const val CAPTURE_DISPOSAL_REFUSED = "CAPTURE_DISPOSAL_REFUSED"

        /**
         * The code of a capture that asked for the ingest agent's stdout.
         *
         * It is not the code of a refusal of the transcript: the transcript was never started.
         */
        const val CAPTURE_AGENT_MODE_REFUSED = "CAPTURE_AGENT_MODE_REFUSED"
    }
}
