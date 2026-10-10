package dev.rubentxu.pipeline.v2.output

/**
 * Why an output read was refused.
 *
 * A **closed** ADT on purpose: a caller must handle every reason, so adding a new refusal mode is a
 * compile error at the call site rather than a silent fallthrough. That matters most for
 * [ForeignStream], which is the case an implementation is most tempted to paper over by returning
 * an empty page — an empty page is a valid answer, and conflating it with "wrong stream" would let
 * a caller read position 0 of a different stream and believe it had read nothing.
 *
 * @see ADR-M1 §D3
 */
sealed interface OutputRefusal {

    /**
     * The cursor names [actual] but the read was addressed to [expected].
     *
     * A cursor from stream A is not a valid position in stream B even at offset 0. The store
     * refuses instead of clamping, because a clamped read is a silent wrong answer.
     */
    data class ForeignStream(
        val expected: OutputStreamId,
        val actual: OutputStreamId,
    ) : OutputRefusal

    /** No stream with this id has ever been opened. */
    data class UnknownStream(val stream: OutputStreamId) : OutputRefusal

    /**
     * The cursor's committed offset is beyond the stream's committed extent.
     *
     * This is not corruption: it is what a cursor looks like after the bytes it named were
     * discarded by a recovery that released an unused reservation. The refusal is closed so the
     * caller is forced to re-anchor rather than to read past the end.
     */
    data class OffsetBeyondCommitted(
        val requested: Long,
        val committed: Long,
    ) : OutputRefusal

    /** A range read whose end precedes its start, or whose size is not positive. */
    data class InvalidRange(val from: Long, val to: Long) : OutputRefusal

    /** The read was attempted before [OutputRecoveryPort.recover] completed. O3. */
    data object RecoveryNotCompleted : OutputRefusal

    /**
     * A committed offset exists that the payload cannot back. I4.
     *
     * This was an `IOException` in the first version, which was a hole in the closed ADT: a
     * caller that handles every refusal still got an exception out of a total function. A short
     * page would be worse still, because it is indistinguishable from a complete one — so the
     * answer is a refusal that says the bytes are missing, not a page that silently omits them.
     */
    data class DanglingCommit(
        val requestedEnd: Long,
        val readableBytes: Long,
    ) : OutputRefusal

    /**
     * M1-B — a follow's declared stream lost retention between two polls.
     *
     * Surfaced by [dev.rubentxu.pipeline.v2.output.follow.OutputFollower.open]
     * when a stream the consumer was tailing was pruned by the
     * retention policy. The cursor position the consumer held is
     * preserved as [lastCommitted] so the consumer can decide
     * whether to reset-and-retry or escalate; the follow does NOT
     * silently emit a `next == null` page and pretend nothing was lost.
     */
    data class StreamLostRetention(
        val stream: OutputStreamId,
        val lastCommitted: Long,
    ) : OutputRefusal

    /**
     * M1-B — the consumer closed the follow handle. Surfaced as a
     * refusal when the follow's iterator was still being driven by
     * another consumer; the public contract is that the close is
     * idempotent and the refusal is the final event of the
     * cancelled follow.
     */
    data class FollowCancelled(val runId: String) : OutputRefusal

    /**
     * M1-B — the underlying storage failed during a follow poll.
     *
     * The follow contract is total: a thrown exception from
     * [OutputReadPort.read], [OutputFrameIndex.framesOfRun] or
     * [OutputTailPort.tailState] is caught at the follower boundary
     * and surfaced as this refusal with a short diagnostic [cause].
     * The M1-A review notes that an IOException-as-control-flow is
     * a hole in the closed ADT, and the parallel port on the event
     * side already exposes
     * [dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal.StorageError]
     * for the same reason; the Output side mirrors it.
     */
    data class StorageError(val cause: String) : OutputRefusal
}

/** A read that either produced a bounded page or was refused. */
sealed interface OutputReadResult {
    data class Page(val page: OutputPage) : OutputReadResult
    data class Refused(val reason: OutputRefusal) : OutputReadResult
}
