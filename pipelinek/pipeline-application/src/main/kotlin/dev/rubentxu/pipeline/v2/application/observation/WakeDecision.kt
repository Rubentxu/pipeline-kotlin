package dev.rubentxu.pipeline.v2.application.observation

/**
 * What the live drain does between two reads of the output plane.
 *
 * The decision has exactly one job: never let a follower sleep while data is
 * flowing, and never let it spin while nothing is. A page that carried
 * records means the plane is producing — the next read may already have
 * bytes waiting, so parking there adds the poll interval to the latency of
 * every chunk of a chatty build. A page that carried nothing means the plane
 * is quiet for now — spinning on it would burn a core to learn nothing, so
 * the follower parks until the safety-poll deadline.
 *
 * The deadline is armed on every park and never disabled: a follower that
 * receives no hints at all behaves exactly like a poller, which is the
 * property that makes hints an optimization rather than a dependency.
 */
sealed interface WakeDecision {

    /** The last read produced records; read again immediately, without parking. */
    data object ReadFromCursor : WakeDecision

    /** The last read produced nothing; park until a hint or the deadline, then re-read. */
    data class AwaitHintOrDeadline(val deadlineMs: Long) : WakeDecision
}

/**
 * Decide the next action of a follower from what its last read produced.
 * Pure: the caller owns the clock, the hints and the cursor; this only names
 * the policy, so the policy is testable without any of them.
 */
fun wakePlan(recordsInLastRead: Int, pollDeadlineMs: Long): WakeDecision = when {
    recordsInLastRead > 0 -> WakeDecision.ReadFromCursor
    else -> WakeDecision.AwaitHintOrDeadline(pollDeadlineMs)
}
