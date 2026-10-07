package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.output.OutputTailState

/**
 * "Something moved; go and look at the durable authority from where you were."
 *
 * ## Why a wakeup carries no irreplaceable payload
 *
 * A wakeup names a POSITION and nothing else — no bytes, no events, no records. That is what makes
 * losing one harmless, and it is the only reason coalescing is legal at all:
 *
 * ```text
 * a dropped wakeup costs a wakeup, never an observation
 * ```
 *
 * The consumer's recovery step is always "read the durable authority from my own cursor to the
 * position I was handed", so a wakeup that never arrived is repaired by the next poll, and a
 * consumer that missed a hundred of them recovers exactly what it would have read anyway. That
 * property is what the constitution permits ("DROP/coalesce wakeup -> permitido") and it is the
 * reason records are never conflated: a record IS the observation, so dropping one would be losing
 * the product.
 *
 * ## Why there are two cases and never a merged one
 *
 * [EventsCommitted] is a position in the event store's sequence; [OutputAdvanced] is a position in
 * the output observation ordinal. Merging them into one "position" would invent a universal clock
 * across two planes that have no shared order — the same fabrication that keeps `ObservationView.FULL`
 * refused. So coalescing is defined only WITHIN a lane, and a lane crossing is not a merge.
 *
 * The run id is on the type rather than on the consumer because a wakeup bus can carry several runs,
 * and two runs' sequences are not comparable either: run A at sequence 900 says nothing about run B
 * at sequence 12.
 */
sealed interface ObservationWakeup {

    /** Which run moved. Never compared with another run's positions. */
    val runId: String

    /** The event store's sequence advanced to at least [lastSequence]. */
    data class EventsCommitted(
        override val runId: String,
        val lastSequence: Long,
    ) : ObservationWakeup {
        init {
            require(lastSequence >= 0) { "lastSequence must be non-negative, got $lastSequence" }
        }
    }

    /** The output frame index published at least up to [lastOrdinal]. */
    data class OutputAdvanced(
        override val runId: String,
        val lastOrdinal: Long,
    ) : ObservationWakeup {
        init {
            require(lastOrdinal >= 0) { "lastOrdinal must be non-negative, got $lastOrdinal" }
        }
    }
}

/**
 * Folds [newer] into [current] keeping the position furthest along.
 *
 * `null` is "nothing seen yet", which is what a consumer holds before its first wakeup.
 *
 * ## What happens when the two are NOT comparable
 *
 * Different runs, or different lanes, cannot be merged into one position without inventing a clock.
 * In those cases the newer one wins and the older is dropped — and that is safe precisely because of
 * the property above: a dropped wakeup costs a wakeup, not an observation. The consumer recovers by
 * reading from its own cursor, and the authority it reads from still holds everything.
 *
 * Keeping the OLDER one instead would be the dangerous choice and is the mutation this contract is
 * written to prevent: a consumer waiting for `ordinal 900` that has been handed `ordinal 12` stops
 * early and reports a run as finished while it is still writing.
 */
fun coalesceWakeup(current: ObservationWakeup?, newer: ObservationWakeup): ObservationWakeup = when {
    current == null -> newer
    current.runId != newer.runId -> newer
    else -> when {
        current is ObservationWakeup.EventsCommitted && newer is ObservationWakeup.EventsCommitted ->
            if (newer.lastSequence > current.lastSequence) newer else current

        current is ObservationWakeup.OutputAdvanced && newer is ObservationWakeup.OutputAdvanced ->
            if (newer.lastOrdinal > current.lastOrdinal) newer else current

        else -> newer
    }
}

/**
 * Whether a follower should read again, or the tail is final.
 *
 * ## The three answers a follower needs, and where each comes from
 *
 * ```text
 * more frames right now?   the reader's own page      -> keep reading
 * can bytes still arrive?  OutputTailState per stream  -> keep reading
 * did the run end?          the run plane, NOT here
 * ```
 *
 * This type answers the first two and refuses to answer the third. `Sealed` says no further bytes
 * will be written to THAT stream; it says nothing about whether the run succeeded, and it is not read
 * as an outcome — that duplication is exactly what `ADR-M1 §D2` forbids and what `OutputTailState`
 * was built to avoid.
 *
 * ## Why a null tail state means "keep reading"
 *
 * `tailState` returns `null` for a stream this store has never heard of, and `null` is NOT `Sealed`.
 * A follower that treated "I don't know" as "finished" would stop on a stream it has merely failed to
 * ask about, which is the failure mode with the worst consequence: it looks exactly like a
 * successfully completed follow. Unknown is the safe direction, and the reverse is the mutation.
 */
sealed interface FollowDecision {

    /** Something may still arrive; read again. */
    data object ReadAgain : FollowDecision

    /** No frame pending and every stream this follower knows about is sealed. */
    data object Finished : FollowDecision
}

/**
 * Pure decision over what the follower has already read.
 *
 * @param moreFrames whether the last page was truncated by the window.
 * @param tailStates the tail state of every stream this follower has seen a frame for. `null` entries
 *   are streams whose state could not be established, and they keep the follower going.
 */
fun followDecision(
    moreFrames: Boolean,
    tailStates: Collection<OutputTailState?>,
): FollowDecision = when {
    moreFrames -> FollowDecision.ReadAgain
    // An empty history means no stream was ever observed, which is not evidence that all of them
    // are sealed. A follower that has read nothing has not proven the run finished.
    tailStates.isEmpty() -> FollowDecision.ReadAgain
    tailStates.any { state -> state !is OutputTailState.Sealed } -> FollowDecision.ReadAgain
    else -> FollowDecision.Finished
}