package dev.rubentxu.pipeline.v2.application.observation

import java.util.concurrent.ArrayBlockingQueue

/**
 * How a parked follower came back, and what it should do about it.
 *
 * [Hint] and [Deadline] both mean "read the authority again now" — the
 * difference is only why it is time. A follower that treats them the same is
 * correct; a follower that reads ONLY on hints is broken by design, because
 * hints are allowed to be dropped.
 */
sealed interface ParkOutcome {
    data object Hint : ParkOutcome
    data object Deadline : ParkOutcome

    /** The parking thread was interrupted; stop promptly and keep the flag set. */
    data object Interrupted : ParkOutcome
}

/**
 * A bounded, in-process signal that tells one run's observer "the output
 * plane may have moved; go and look".
 *
 * ## A hint, never a fact
 * The signal carries no position, no bytes and no records. What it grants is
 * latency, not knowledge: the reader that wakes on it re-reads the durable
 * authority from its own cursor, exactly as it would at the poll deadline.
 * That is what makes dropping legal — a signal that is lost, coalesced or
 * published before the observer was parked costs nothing but time, because
 * the safety deadline is armed on every park and a follower without a single
 * hint behaves exactly like a poller.
 *
 * ## Why the producer can never be slowed down
 * [signal] never blocks and never throws: the queue is bounded and an offer
 * that does not fit is dropped. The producer is a run's output path, and a
 * slow consumer must never reach it — the consumer-backpressure law, made
 * structural. A dropped signal is repaired by the deadline, not by memory.
 *
 * ## Scope
 * One signal instance belongs to one run's observer. There is no bus, no
 * dispatch by run id and no cross-run traffic to filter: the run that writes
 * is the run whose observer parks on it.
 */
class WakeupSignal(capacity: Int = 64) {

    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    private val pending = ArrayBlockingQueue<Boolean>(capacity)

    /**
     * Announce that the plane may have moved. Returns false when the signal
     * was dropped because the queue was full; callers ignore the result, and
     * that is the contract — the return value exists for tests, not for
     * handling.
     */
    fun signal(): Boolean = pending.offer(true)

    /**
     * Park until a hint arrives or [timeoutMs] elapses, whichever comes first.
     * Both outcomes mean "time to read again"; only [ParkOutcome.Interrupted]
     * means stop, and it restores the interrupt flag for the caller's owner.
     */
    fun await(timeoutMs: Long): ParkOutcome = try {
        if (pending.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
            ParkOutcome.Hint
        } else {
            ParkOutcome.Deadline
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        ParkOutcome.Interrupted
    }
}
