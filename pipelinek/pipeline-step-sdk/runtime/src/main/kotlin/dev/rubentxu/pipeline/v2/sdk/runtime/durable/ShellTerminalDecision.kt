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
 * out", not "durable shell timed out"— so it keeps building its own. The legacy projections in
 * `DurableTaskTerminalAdapter.kt` translate the old shapes and are on their way out.
 */
internal class ShellTerminalDecision(
    private val opId: String,
    private val controlDir: Path,
) {

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
     * unreadable.
     */
    fun forPollOutcome(
        outcome: ShellPollOutcome,
        capturedStdout: String?,
    ): DurableTaskTerminal = when (outcome) {
        is ShellPollOutcome.Published -> DurableTaskTerminal.Exited(
            exitCode = outcome.exitCode,
            output = DurableTaskOutput(
                controlDir = controlDir.toString(),
                capturedStdout = capturedStdout,
            ),
        )

        ShellPollOutcome.Expired -> expired()

        ShellPollOutcome.ResultNotAnExitCode -> lost(RESULT_NOT_AN_EXIT_CODE)

        ShellPollOutcome.WrapperEnded -> lost(WRAPPER_ENDED_WITHOUT_RESULT)

        ShellPollOutcome.CeilingElapsed -> lost(CEILING_ELAPSED)

        // The poll only hands this over when it was interrupted, and an interrupted poll knows
        // nothing: the outcome cannot be established, so it is LOST.
        ShellPollOutcome.KeepWaiting -> lost(NO_FACT)
    }

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

        /** The deadline elapsed with nothing published. */
        const val CEILING_ELAPSED =
            "durable shell did not publish a result before its deadline elapsed"

        /** The poll was interrupted before any fact was known. */
        const val NO_FACT = "durable shell poll ended without a fact"

        /** The token of a child that is holding the transcript open. */
        const val STUCK_CHILD_CAUSE = "transcript-pump-still-alive"

        /** The token of a machine that did not keep up. */
        const val OUT_OF_TIME_CAUSE = "transcript-drain-out-of-time"
    }
}
