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
 * The type answers the first two and never carries the third. `Sealed` says no further bytes will be
 * written to THAT stream; it says nothing about whether the run succeeded, and it is not read as an
 * outcome — that duplication is exactly what `ADR-M1 §D2` forbids and what `OutputTailState` was built
 * to avoid. The THIRD question is asked, but it is asked of a different authority and arrives as an
 * argument to [followDecision]: "did the run end?" belongs to the run plane, and a follow decision
 * that had to infer it from stream tails would be inferring a run's fate from its silence.
 *
 * That is why `FollowDecision.Finished` is a claim about the OUTPUT lane and nothing more — the same
 * distinction the run lane draws with `FollowOutcome.ReachedRunFinish`, which is a different case for
 * exactly this reason. `ReachedSealedOutput` says every stream is sealed; `ReachedRunFinish` says the
 * run ended. Making either imply the other is the duplication this whole model exists to avoid.
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
 * ## What [runFinished] is FOR, and what it must never become
 *
 * [runFinished] is a rescue for the one case where the output plane has NO evidence at all: a run
 * that owns no stream, because it wrote nothing. With lazy stream declaration (a stream is created
 * on its first byte) that is not a corner case, it is every silent step — `sh("true")`. Both facts
 * look identical from the output plane, an empty tail list, and only the run plane separates them:
 *
 *  * empty tails, run still going  → [FollowDecision.ReadAgain]. Nothing has been observed, and
 *    nothing observed is not evidence of completion.
 *  * empty tails, run finished     → [FollowDecision.Finished]. This is `sh("true")`, and it is
 *    **UAT-R1-01**: a silent step terminates and its follower knows the end.
 *
 * ## Which UAT row this is
 *
 * **UAT-R1-01** — "`sh("true")`, sin stdout/stderr, termina y su follower conoce el fin" — from the
 * OBS-R1 mandate §1.5. The published repo matrices (`OBS-PC-101..107`, `OBS-PC-201..207`) do not
 * contain this row; the mandate does, and the mandate is the requirement. An earlier revision of this
 * file claimed the row did not exist anywhere and removed the reference. That was wrong, and wrong in
 * the expensive direction: it read as if the behaviour were optional, when the mandate makes it
 * mandatory. The `docs/` tree was searched and came back empty; the rows were in the mandate, not in
 * the tree.
 *
 * What this file discharges is the DECISION half of UAT-R1-01. The run half — an installed pipeline
 * whose silent step terminates a real follower — is not discharged here and is tracked as such.
 *
 * ## Why it is NOT a conjunct on the sealed case
 *
 * When at least one stream is known, sealing already answers the question this function asks, and
 * requiring the run to have finished as well would be WRONG rather than merely stricter. Three
 * reasons, in order of force:
 *
 *  1. It would collapse two distinct outcomes into one. `Finished` here means the output lane is
 *     final; the run lane reports `ReachedRunFinish` for the run's fate. Two cases exist precisely
 *     because those are two facts, and `ADR-M1 §D2` forbids reading one as the other.
 *  2. It would make `--view console --follow` unterminatable without an event plane. A console-only
 *     lane has no event store to ask, so `hasRunFinished` can only answer "I do not know" — and
 *     conflating that with "keep reading forever" hangs the consumer instead of ending it.
 *  3. "Every stream is sealed" is itself the answer the caller asked for. A follower re-reads the
 *     tail list every round, so a stream declared later is observed later; the sealed case is not
 *     claiming the run is over, only that the output plane has nothing pending.
 *
 * The rejected alternative was declaring a stream for a silent step so there would be something to
 * seal. That invents an observation to buy a termination, which is why [runFinished] exists instead.
 *
 * @param moreFrames whether the last page was truncated by the window.
 * @param tailStates the tail state of every stream this run owns. `null` entries are streams whose
 *   state could not be established, and they keep the follower going.
 * @param runFinished whether the durable execution authority says the run reached a terminal state.
 *   Consulted ONLY when [tailStates] is empty. A lane that cannot answer must leave this `false`,
 *   because `false` here means "no evidence", never "the run is still going".
 */
fun followDecision(
    moreFrames: Boolean,
    tailStates: Collection<OutputTailState?>,
    runFinished: Boolean,
): FollowDecision = when {
    moreFrames -> FollowDecision.ReadAgain
    // The run owns no stream, so the output plane says nothing at all and the run plane must answer.
    tailStates.isEmpty() && !runFinished -> FollowDecision.ReadAgain
    // Any stream that is open, or whose state is unknown, still owes bytes. Unreachable for an empty
    // list, and harmless there: `any` over an empty collection is false.
    tailStates.any { state -> state !is OutputTailState.Sealed } -> FollowDecision.ReadAgain
    else -> FollowDecision.Finished
}
