package com.pipeline.v2.sdk.runtime.durable

import com.pipeline.v2.sdk.Effect
import com.pipeline.v2.sdk.ReplayPolicy

/**
 * Interface for effect-aware replay decisions.
 *
 * ## M3-R1 → M3-R2 Contract
 *
 * This interface is stable for M3-R2 consumption per [design.md §8].
 *
 * ## Decision matrix
 *
 * | ReplayPolicy | Effects               | Has Journal Entry | Journaled Outcome | Decision |
 * |--------------|----------------------|-------------------|-------------------|--------- |
 * | MEMOIZED     | READ_ONLY            | true              | SUCCEEDED         | SKIP     |
 * | MEMOIZED     | READ_ONLY            | true              | !SUCCEEDED        | RERUN    |
 * | MEMOIZED     | READ_ONLY            | false             | —                 | RERUN    |
 * | MEMOIZED     | EXECUTES_SUBPROCESS  | any               | any               | RERUN    |
 * | RERUN        | any                  | any               | any               | RERUN    |
 * | NEVER        | any                  | any               | any               | ABORT    |
 * | any          | ABORTS_PIPELINE      | any               | any               | ABORT    |
 * | any          | —                    | false (MEMOIZED)  | —                 | ABORT    |
 * | any          | FAILED (journaled)   | true              | FAILED            | ABORT    |
 *
 * @see <a href="design.md §E4-06">Design §E4-06</a>
 */
interface EffectReplayPolicy {
    /**
     * Decides whether to skip, rerun, or abort a durable operation.
     *
     * @param replayPolicy    The step's configured replay policy.
     * @param effects         The observed effects of the step execution.
     * @param hasJournalEntry Whether a journal entry exists for this operation.
     * @param journaledOutcome The [com.pipeline.v2.domain.durable.OperationStatus] from the journal,
     *                        or `null` if no entry exists.
     * @return The [ReplayDecision].
     */
    fun decide(
        replayPolicy: ReplayPolicy,
        effects: Set<Effect>,
        hasJournalEntry: Boolean,
        journaledOutcome: com.pipeline.v2.domain.durable.OperationStatus?,
    ): ReplayDecision
}

/**
 * Default effect-aware replay policy implementation.
 *
 * @see <a href="design.md §E4-06">Design §E4-06</a>
 */
class DefaultEffectReplayPolicy : EffectReplayPolicy {

    /**
     * Decides whether to skip, rerun, or abort a durable operation.
     *
     * @param replayPolicy    The step's configured replay policy.
     * @param effects         The observed effects of the step execution.
     * @param hasJournalEntry Whether a journal entry exists for this operation.
     * @param journaledOutcome The [com.pipeline.v2.domain.durable.OperationStatus] from the journal,
     *                        or `null` if no entry exists.
     * @return The [ReplayDecision].
     */
    override fun decide(
        replayPolicy: ReplayPolicy,
        effects: Set<Effect>,
        hasJournalEntry: Boolean,
        journaledOutcome: com.pipeline.v2.domain.durable.OperationStatus?,
    ): ReplayDecision {
        // RERUN policy: if journaled outcome is SUCCEEDED, skip (reconciliation already marked it).
        if (replayPolicy == ReplayPolicy.RERUN) {
            if (journaledOutcome == com.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED) {
                return ReplayDecision.SKIP
            }
            return ReplayDecision.RERUN
        }

        // NEVER policy always aborts.
        if (replayPolicy == ReplayPolicy.NEVER) {
            return ReplayDecision.ABORT
        }

        // ABORTS_PIPELINE effect always aborts.
        if (Effect.ABORTS_PIPELINE in effects) {
            return ReplayDecision.ABORT
        }

        // If MEMOIZED policy with no journal entry, rerun.
        if (replayPolicy == ReplayPolicy.MEMOIZED && !hasJournalEntry) {
            return ReplayDecision.RERUN
        }

        // If MEMOIZED policy with journal entry.
        if (replayPolicy == ReplayPolicy.MEMOIZED && hasJournalEntry) {
            // READ_ONLY + SUCCEEDED → SKIP.
            if (Effect.READ_ONLY in effects && effects.none { it == Effect.EXECUTES_SUBPROCESS }) {
                if (journaledOutcome == com.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED) {
                    return ReplayDecision.SKIP
                }
            }
            // Any non-succeeded outcome → RERUN.
            if (journaledOutcome != com.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED) {
                return ReplayDecision.RERUN
            }
            // Missing journal entry for MEMOIZED is already handled above.
        }

        // EXECUTES_SUBPROCESS always reruns.
        if (Effect.EXECUTES_SUBPROCESS in effects) {
            return ReplayDecision.RERUN
        }

        // Default: rerun.
        return ReplayDecision.RERUN
    }
}
