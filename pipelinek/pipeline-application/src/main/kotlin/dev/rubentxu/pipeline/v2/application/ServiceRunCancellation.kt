package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.control.CancelOutcome
import dev.rubentxu.pipeline.v2.runtime.control.CancelReason
import dev.rubentxu.pipeline.v2.runtime.control.CancelRefusal
import dev.rubentxu.pipeline.v2.runtime.control.RuntimeControlPort
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The answer of a cancellation.
 *
 * ## What the three cases are
 *
 * [Cancelled] is a run THIS call closed. [AlreadyCancelled] is a run that a
 * previous call closed: it is an answer and not a refusal, because a
 * cancellation repeated is not an error. [Refused] is a run that was not
 * closed, and it carries the NAME of the fact that stopped it.
 */
internal sealed interface ServiceCancelOutcome {

    /** This call closed the run, and this is when it reached its terminal. */
    data class Cancelled(val terminalAtMs: Long) : ServiceCancelOutcome

    /** The run was already closed, and this is when it reached its terminal. */
    data class AlreadyCancelled(val terminalAtMs: Long) : ServiceCancelOutcome

    /** The run was not closed, and this says why. */
    data class Refused(val reason: String, val detail: String) : ServiceCancelOutcome
}

/**
 * The cancellation of a run, over the socket.
 *
 * ## The two halves, and the order
 *
 * A run has TWO owners and the product already has both: the supervisor
 * owns the PROCESS —it launched the child and only it can end it— and the
 * control port owns the durable FACT —it writes the terminal and seals the
 * output, and it is the one that makes the terminal durable.
 *
 * The order is NOT free. The port refuses to cross a live lease, and the
 * lease of a supervised run is held by the child while the child lives, so
 * a cancellation that wrote the terminal first would be refused with "the
 * lease is held by another" —the truth, and a truth that would make the
 * cancellation useless. The child is ended first, and the terminal is
 * written after.
 *
 * ## Why the same path RECONCILES a lost run
 *
 * Because a run whose service died has no child here: the first half finds
 * nothing to end, and the second half closes the run by taking the lease of
 * a dead owner, which is what the takeover of a dead owner is for. One
 * path, two situations, and the socket does not need a second verb for the
 * second one.
 *
 * ## What it never does
 *
 * It never crosses a live lease, and it never invents a terminal: the
 * durable fact is written by the port that owns it, and a refusal is
 * answered with its name.
 *
 * ## Why one close at a time
 *
 * Because a lease and a terminal may only be owned by one closer at a time,
 * and the service closes the runs of many clients. The lock that keeps it so
 * is described where it is.
 */
internal class ServiceRunCancellation(
    private val ender: ServiceRunEnder,
    private val control: RuntimeControlPort,
) {

    /**
     * The lock that lets the service close ONE run at a time.
     *
     * ## Why the closes are serialized
     *
     * Because two facts may only be owned by one closer at a time, and both
     * of them are facts the product keeps, not facts this service invented.
     *
     * The first is the **lease**. The lease store keeps the lock it took in
     * ONE place, because it is the only thing that knows how to give it back:
     * a store that took two leases at once would give back the second when
     * the first is released, and the run of the first would stay locked for
     * ever. A run that can never be leased again is a run that can never be
     * closed nor recovered, and that is worse than a run that failed loudly.
     *
     * The second is the **terminal**. A cancellation repeated has to read the
     * terminal that the first one made durable, and a second one that arrives
     * while the first is still writing would find no terminal at all. The
     * lease would stop it — that is what the lease is for — but the client
     * would be told that "another process is publishing the run", and the
     * process it would name is THIS service, in the middle of the
     * cancellation the client just asked for. A close that WAITS answers
     * `already-cancelled` with the terminal that is already there, which is
     * what a repeated close has to answer.
     *
     * ## What the wait covers
     *
     * Only the durable work: the receipt, the seal and the terminal. The
     * child has already been ended before the lock is taken, because the
     * ending is the slow half and it is the half that does not touch the
     * lease of anybody.
     */
    private val closing = ReentrantLock()

    /**
     * Cancels the run [runId] for the [reason] given.
     *
     * The [reason] is the operator's own words for why, and it is optional:
     * a cancellation without one carries the product's own word for it.
     */
    fun cancel(runId: String, reason: String?): ServiceCancelOutcome {
        // The PROCESS first: the child holds the lease, and the terminal
        // can only be written when the lease is free.
        ender.end(runId)
        val outcome = closing.withLock { control.cancel(runId, reasonOf(reason)) }
        return when (outcome) {
            is CancelOutcome.Cancelled -> ServiceCancelOutcome.Cancelled(outcome.terminalAtMs)

            is CancelOutcome.AlreadyCancelled ->
                ServiceCancelOutcome.AlreadyCancelled(outcome.terminalAtMs)

            is CancelOutcome.Refused -> ServiceCancelOutcome.Refused(
                reasonOf(outcome.refusal),
                detailOf(outcome.refusal),
            )
        }
    }

    companion object {

        /**
         * The word that says THIS call closed the run.
         */
        const val OUTCOME_CANCELLED: String = "cancelled"

        /**
         * The word that says the run was ALREADY closed.
         *
         * It is an answer and not a refusal: a cancellation repeated is not
         * an error, and a client that retries a cancel whose answer was lost
         * reads a closed run.
         */
        const val OUTCOME_ALREADY_CANCELLED: String = "already-cancelled"

        /**
         * The reason the port's refusal [refusal] travels with.
         *
         * ## Why the names live HERE and not in the published artifact
         *
         * Because they are the SERVICE's vocabulary, and the artifact that
         * publishes the wire publishes the SHAPE of the refusal —a reason
         * and a detail— and not the closed set of words the reason may take.
         * A client reads the word to decide what to do next, and a word it
         * does not know is a word it has to be able to carry without
         * understanding: the refusal is still a refusal.
         */
        fun reasonOf(refusal: CancelRefusal): String = when (refusal) {
            is CancelRefusal.UnknownRun -> "the-run-is-unknown"
            is CancelRefusal.RunTerminal -> "the-run-is-already-terminal"
            is CancelRefusal.LeaseHeldByAnother -> "the-lease-is-held-by-another"
            is CancelRefusal.IncompatibleRunState -> "the-run-state-is-incompatible"
            is CancelRefusal.JournalUnavailable -> "the-journal-is-unavailable"
            is CancelRefusal.StorageError -> "the-storage-failed"
        }

        /**
         * The sentence the port's refusal [refusal] travels with.
         *
         * It is the service's own words about the fact, and it names the
         * run or the owner when the refusal names one: a client that is
         * told that another process is publishing the run can look for it.
         */
        fun detailOf(refusal: CancelRefusal): String = when (refusal) {
            is CancelRefusal.UnknownRun -> "the service does not know the run ${refusal.runId}"
            is CancelRefusal.RunTerminal ->
                "the run already reached the terminal '${refusal.outcome}'"

            is CancelRefusal.LeaseHeldByAnother ->
                "another process is publishing the run: ${refusal.ownerId}"

            is CancelRefusal.IncompatibleRunState -> refusal.details
            is CancelRefusal.JournalUnavailable -> refusal.cause
            is CancelRefusal.StorageError -> refusal.cause
        }

        /**
         * The reason the port receives, for the [text] the client gave.
         *
         * A cancellation without a reason carries the product's own word for
         * it, and one with a reason carries the operator's words: the reason
         * is recorded verbatim in the run's own history.
         */
        fun reasonOf(text: String?): CancelReason =
            if (text == null) CancelReason.UserRequested else CancelReason.Annotated(text)
    }
}
