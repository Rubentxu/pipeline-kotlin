package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy

/**
 * Interface for effect-aware replay decisions.
 *
 * ## M3-R1 → M3-R2 Contract
 *
 * This interface is stable for M3-R2 consumption per [design.md §8].
 *
 * ## Decision matrix — ADR-0103 D1
 *
 * This table is **normative**, and so is its ORDER. It previously contradicted
 * the implementation in two ways, both of which survived because nothing
 * asserted it: the `RERUN` row did not match the enum's own documentation, and
 * the `ABORTS_PIPELINE` row was unreachable for any `RERUN` Step because the
 * policy branch returned first. The table is now pinned by
 * `EffectReplayPolicyTableFitnessTest`.
 *
 * The order separates **admission** from **replay**, and that separation is the
 * point: `ABORTS_PIPELINE` means "this Step, when executed, aborts the
 * pipeline", so it must not outrank the first execution or the abort would be
 * dropped silently.
 *
 * ```text
 * 1  no journal entry (first execution)            EXECUTE
 * 2  journalled + ABORTS_PIPELINE in effects       ABORT
 * 3  journalled + NEVER                            ABORT
 * 4  journalled + RERUN + reusable completion      SKIP
 * 5  journalled + RERUN + not reusable             EXECUTE
 * 6  journalled + MEMOIZED + purely READ_ONLY
 *     + reusable completion                        SKIP
 * 7  journalled + MEMOIZED + purely READ_ONLY
 *     + not reusable                               EXECUTE
 * 8  journalled + MEMOIZED + EXECUTES_SUBPROCESS
 *     or WRITES_WORKSPACE (incl. mixed sets)       EXECUTE
 * ```
 *
 * **Reusable completion** is `[isReusableCompletion]`: `SUCCEEDED` and — since P2 of the
 * Runtime Observation Contract Closure — `UNSTABLE`. An unstable run COMPLETED: its work is
 * done, its typed output is journalled, and replaying it would re-execute finished work only
 * because its durable status is not green. `FAILED`, `FAILED_TIMEOUT`, `ABORTED`, `DIVERGENT`
 * and `LOST` are not completions and stay non-reusable; `ABORTED` in particular keeps the
 * pre-existing non-reuse rule rather than gaining reuse by analogy. A reusable row is served
 * by decoding the persisted typed output — the semantic outcome is then derived from the
 * carrier (`outcomeOf`), never from the durable status, so an unstable reuse reports unstable.
 *
 * A **mixed** effect set is never memoisable: only a set that is purely
 * `READ_ONLY` may `SKIP`. An **empty** set is not memoisable either — an
 * executor that declared no effect has said nothing about purity.
 *
 * Note on names: `ReplayPolicy.RERUN` means "reuse a journalled reusable completion",
 * the opposite of what the name suggests. ADR-0103 D2a fixes the
 * documented contract; D2b defers the rename, because the enum name is inside
 * the fingerprint hash and renaming it would migrate every operation that
 * declares it on both the canonical and the scripted history.
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
     * @param journaledOutcome The [dev.rubentxu.pipeline.v2.domain.durable.OperationStatus] from the journal,
     *                        or `null` if no entry exists.
     * @return The [ReplayDecision].
     */
    fun decide(
        replayPolicy: ReplayPolicy,
        effects: Set<Effect>,
        hasJournalEntry: Boolean,
        journaledOutcome: dev.rubentxu.pipeline.v2.domain.durable.OperationStatus?,
    ): ReplayDecision
}

/**
 * The single classification of durable completions whose work is done and whose journalled
 * result may be served again instead of re-executing the handler.
 *
 * This is the ONE authority for "reusable": rules 4 and 5 of the decision matrix and the
 * memoisation rule all read it, and nothing else re-derives the set. `UNSTABLE` joined
 * `SUCCEEDED` when P2 closed the recorded replay debt (S4-D2 frontier): an unstable run is a
 * finished run, and refusing to reuse it re-executed completed work purely because the durable
 * status was not green. Every other status — failed, timed out, aborted, divergent, lost, or
 * still in flight — is not a completion and stays non-reusable. `ABORTED` keeps the existing
 * non-reuse behaviour; it is not promoted by analogy.
 */
fun OperationStatus.isReusableCompletion(): Boolean =
    this == OperationStatus.SUCCEEDED || this == OperationStatus.UNSTABLE

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
     * @param journaledOutcome The [dev.rubentxu.pipeline.v2.domain.durable.OperationStatus] from the journal,
     *                        or `null` if no entry exists.
     * @return The [ReplayDecision].
     */
    override fun decide(
        replayPolicy: ReplayPolicy,
        effects: Set<Effect>,
        hasJournalEntry: Boolean,
        journaledOutcome: dev.rubentxu.pipeline.v2.domain.durable.OperationStatus?,
    ): ReplayDecision {
        val reusable = journaledOutcome != null && journaledOutcome.isReusableCompletion()

        // ADR-0103 D1 — the table is normative and its ORDER is the contract.
        // This was a cascade of `if`s whose order had grown accidental semantics
        // twice: the `RERUN` and `NEVER` branches returned before the effect set
        // was consulted, so `ABORTS_PIPELINE` could be pre-empted by a policy
        // name. Naming each rule makes the precedence reviewable instead of
        // emergent.
        return when {
            // 1. ADMISSION — a first execution is never suppressed by the replay
            //    layer. `ABORTS_PIPELINE` deliberately does not outrank this: the
            //    effect means "this Step, when executed, aborts the pipeline", so
            //    refusing to execute it would drop the abort silently. A Step
            //    that never runs never aborts, and `CoreErrorStep` is exactly
            //    this case. Hoisting this rule changes no decision the previous
            //    cascade made for MEMOIZED, RERUN or NEVER, all of which already
            //    executed on a fresh invocation.
            !hasJournalEntry -> ReplayDecision.RERUN

            // 2. CONTAINMENT — an aborting effect is never served from cache and
            //    never re-run under a weaker policy branch. Applies to history
            //    only, which is what rule 1 just excluded.
            Effect.ABORTS_PIPELINE in effects -> ReplayDecision.ABORT

            // 3. NON-REPLAYABLE HISTORY (E-EM-11/NEVER, classification A): NEVER
            //    constrains re-execution of durable history and nothing else.
            replayPolicy == ReplayPolicy.NEVER -> ReplayDecision.ABORT

            // 4. REUSE OF A REUSABLE COMPLETION. The name `RERUN` means the opposite of
            //    what it says: this is reuse, not re-execution. ADR-0103 D2a fixes
            //    the documented contract and pins it with a test; D2b defers the
            //    rename to a durable compatibility epoch, because the enum name is
            //    inside the fingerprint hash. `UNSTABLE` is reusable since P2: the
            //    decoded typed carrier carries the Unstable marker, so the reuse
            //    reports unstable without re-running the handler.
            replayPolicy == ReplayPolicy.RERUN && reusable -> ReplayDecision.SKIP

            // 5. EFFECT-AWARE MEMOISATION. The effect set must be PURELY read-only
            //    (WU-RP-040 R8 category C): a mixed set containing
            //    WRITES_WORKSPACE or EXECUTES_SUBPROCESS must not memoise even
            //    when READ_ONLY is also declared, or the engine would skip
            //    re-writing workspace state. The descriptor's `effects:
            //    List<Effect>` makes mixed sets representable, which is why this
            //    cannot be a `contains(READ_ONLY)` test.
            replayPolicy == ReplayPolicy.MEMOIZED && memoisable(effects) && reusable -> ReplayDecision.SKIP

            // 6. DEFAULT — anything not explicitly reusable is re-executed. Covers
            //    the effectful MEMOIZED rows, every non-completion outcome, and the
            //    empty effect set, which is deliberately NOT memoisable: an
            //    executor that declared no effect has said nothing about purity.
            else -> ReplayDecision.RERUN
        }
    }

    /** PURELY read-only. An empty or mixed set is not memoisable. */
    private fun memoisable(effects: Set<Effect>): Boolean =
        effects.isNotEmpty() && effects.all { it == Effect.READ_ONLY }
}
