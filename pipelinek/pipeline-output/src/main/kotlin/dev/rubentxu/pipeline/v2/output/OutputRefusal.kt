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

    /**
     * M3 — the read crosses a range that has been pruned by
     * [dev.rubentxu.pipeline.v2.output.OutputRetentionPort.prune].
     *
     * Distinct from [OffsetBeyondCommitted]: that case says "the
     * cursor is past the committed extent, re-anchor". This case
     * says "the bytes you named WERE committed and have since been
     * GC'd". The consumer must escalate (re-fetch from a replica
     * whose pin survived, or fail the read).
     *
     * Surfaced by [OutputReadPort.readRange] and
     * [OutputReadPort.readRangeDigested] when the named range's
     * start or end falls past the stream's pinned-and-unpinned
     * retained extent. The [lastCommitted] is the offset of the last
     * byte that was actually retained at the time of the read; the
     * gap is the region `[lastCommitted, requestedRange.last]` for
     * which no bytes are available.
     *
     * @property stream the stream whose retention gap the read crossed
     * @property requestedRange the half-open range the read asked for
     * @property lastCommitted the last retained byte before the gap;
     *                          the read can resume from
     *                          `lastCommitted + 1` if the consumer
     *                          chooses to re-anchor
     */
    data class RetentionGap(
        val stream: OutputStreamId,
        val requestedRange: LongRange,
        val lastCommitted: Long,
    ) : OutputRefusal

    /**
     * M3 — the bytes in [requestedRange] are unparseable: a row exists
     * but its payload does not back it.
     *
     * Distinct from [DanglingCommit]: that case carries the same fact
     * at the *end-of-stream* granularity (the write-side analogue —
     * [OutputRefusal.kt:56-59]); this case carries it at
     * finer, *per-range* granularity on the read side. A consumer
     * that hits `Corrupt` knows exactly which range is unparseable;
     * a consumer that hits `DanglingCommit` only knows the
     * end-of-stream boundary.
     *
     * Distinct from [StorageError]: that case is a transient I/O
     * fault; this case is a *durable* unparseability. The store can
     * retry `StorageError`; `Corrupt` does not get better.
     *
     * @property stream the stream whose bytes are unparseable
     * @property requestedRange the half-open range the read asked for
     * @property reason a bounded diagnostic; the implementation MUST
     *                   NOT include byte data in the reason (a digest
     *                   of bytes does not survive the constructor)
     */
    data class Corrupt(
        val stream: OutputStreamId,
        val requestedRange: LongRange,
        val reason: String,
    ) : OutputRefusal {
        init {
            require(reason.length <= OutputRefusal.CORRUPT_REASON_MAX_LEN) {
                "Corrupt.reason must be <= ${OutputRefusal.CORRUPT_REASON_MAX_LEN} chars, got ${reason.length}"
            }
            require('\n' !in reason) { "Corrupt.reason must not contain newlines" }
        }
    }

    /**
     * M3 — the store cannot answer right now.
     *
     * Distinct from [StorageError]: that case is a transient I/O
     * fault (the read path's `IOException`); this case is a typed
     * declaration that the substrate is *unreachable*. The
     * distinction matters for retry policy: a consumer retries
     * `StorageError` with backoff; a consumer that hits
     * `Unavailable` waits for a wakeup or escalates.
     */
    data object Unavailable : OutputRefusal

    /**
     * M3 — the read-side analogue of the M1-B follow-side
     * [StreamLostRetention].
     *
     * Surfaced by [OutputReadPort.readRange] and
     * [OutputReadPort.readRangeDigested] when a range the read
     * named has been lost to retention (NOT a follow's tail —
     * [StreamLostRetention] is the follow-side case).
     *
     * The two cases are deliberately distinct types so a consumer
     * can switch on them without parsing free-text reasons. The
     * follow-side case means "I was tailing and the tail is gone";
     * this case means "I asked for a range and that range is gone".
     *
     * @property stream the stream whose retention was lost
     * @property requestedRange the half-open range the read asked for
     * @property lastCommitted the last retained byte before the gap;
     *                          mirrors [RetentionGap.lastCommitted]
     */
    data class RangeLostRetention(
        val stream: OutputStreamId,
        val requestedRange: LongRange,
        val lastCommitted: Long,
    ) : OutputRefusal

    companion object {
        /** Bounded length of [Corrupt.reason]. */
        const val CORRUPT_REASON_MAX_LEN: Int = 256
    }
}

/** A read that either produced a bounded page or was refused. */
sealed interface OutputReadResult {
    data class Page(val page: OutputPage) : OutputReadResult
    data class Refused(val reason: OutputRefusal) : OutputReadResult
}
