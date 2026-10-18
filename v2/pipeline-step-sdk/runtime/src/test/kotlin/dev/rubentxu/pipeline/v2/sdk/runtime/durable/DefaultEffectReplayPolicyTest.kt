package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The replay decision is the one place where a durable engine decides whether to *lie* to the
 * operator: a `SKIP` serves a journalled result instead of running the work again. Too many `SKIP`s
 * and a resumed pipeline reports work it never redid; too few and every resume re-executes side
 * effects that already happened.
 *
 * [DefaultEffectReplayPolicy.decide] is therefore a table whose ORDER is the contract, and the order
 * is not derivable from the rules read one at a time. It already contradicted the implementation
 * twice, both times because nothing asserted it: the `RERUN` row did not match the enum's own
 * documentation, and `ABORTS_PIPELINE` was unreachable for a `RERUN` Step because the policy branch
 * returned first. This file is what makes a third contradiction impossible to merge quietly.
 *
 * Each test names the ROW it pins, and the rows are the KDoc's matrix verbatim, so a change to the
 * table is a change to a test that says why. Pure: no journal, no filesystem, no clock — the whole
 * input is four values.
 */
class DefaultEffectReplayPolicyTest {

    private val policy = DefaultEffectReplayPolicy()

    private fun decide(
        replayPolicy: ReplayPolicy = ReplayPolicy.MEMOIZED,
        effects: Set<Effect> = setOf(Effect.READ_ONLY),
        journalled: Boolean = true,
        outcome: OperationStatus? = OperationStatus.SUCCEEDED,
    ) = policy.decide(replayPolicy, effects, journalled, outcome)

    // --- row 1: admission outranks everything -----------------------------------

    @Test
    fun `row 1 a first execution runs, even under NEVER and even when the effect aborts`() {
        // THE discriminator for rule 1. `ABORTS_PIPELINE` means "this Step, WHEN EXECUTED, aborts
        // the pipeline"; refusing to execute it would drop the abort silently, because a Step that
        // never runs never aborts. `CoreErrorStep` is exactly this case, so hoisting this row above
        // the containment row is what keeps a first run of a pipeline with an error step working.
        assertEquals(ReplayDecision.RERUN, decide(ReplayPolicy.NEVER, setOf(Effect.ABORTS_PIPELINE), false, null))
    }

    @Test
    fun `row 1 does not depend on the policy name`() {
        for (candidate in ReplayPolicy.entries) {
            assertEquals(
                ReplayDecision.RERUN,
                decide(candidate, setOf(Effect.READ_ONLY), false, null),
                "policy $candidate must not suppress a first execution",
            )
        }
    }

    // --- row 2: containment outranks the policy branches -------------------------

    @Test
    fun `row 2 an aborting effect is never served from cache and never re-run`() {
        // Every policy is checked, because "outranks" only means something if the row above all
        // three policy branches. `NEVER` and `RERUN` would otherwise answer first.
        for (candidate in ReplayPolicy.entries) {
            assertEquals(
                ReplayDecision.ABORT,
                decide(candidate, setOf(Effect.ABORTS_PIPELINE), true, OperationStatus.SUCCEEDED),
                "policy $candidate must not pre-empt the abort",
            )
        }
    }

    @Test
    fun `row 2 applies to an aborting effect even when nothing is reusable`() {
        // The containment row reads the EFFECT, not the outcome. A failed operation whose effect
        // set contains ABORTS_PIPELINE still aborts: re-running it would re-abort, and serving it
        // from cache is impossible.
        assertEquals(
            ReplayDecision.ABORT,
            decide(ReplayPolicy.MEMOIZED, setOf(Effect.ABORTS_PIPELINE), true, OperationStatus.FAILED),
        )
    }

    // --- row 3: NEVER -----------------------------------------------------------

    @Test
    fun `row 3 NEVER aborts durable history whatever the effect set`() {
        // The row reads the POLICY, not the effect set: three of these would answer SKIP under
        // MEMOIZED or RERUN, and none of them is allowed to soften NEVER.
        val effectSets = listOf(
            setOf(Effect.READ_ONLY),
            setOf(Effect.EXECUTES_SUBPROCESS),
            setOf(Effect.READ_ONLY, Effect.WRITES_WORKSPACE),
            emptySet(),
        )
        for (effects in effectSets) {
            assertEquals(
                ReplayDecision.ABORT,
                decide(ReplayPolicy.NEVER, effects, true, OperationStatus.SUCCEEDED),
                "NEVER must not be softened by the effect set $effects",
            )
        }
    }

    @Test
    fun `the policies that would otherwise serve it, do`() {
        // The counterpart, so row 3 is a statement about NEVER and not about a decider that always
        // aborts. Same input as the row above, other policies, and they reuse.
        assertEquals(ReplayDecision.SKIP, decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), true, OperationStatus.SUCCEEDED))
        assertEquals(ReplayDecision.SKIP, decide(ReplayPolicy.RERUN, setOf(Effect.WRITES_WORKSPACE), true, OperationStatus.SUCCEEDED))
    }

    @Test
    fun `row 3 NEVER aborts even a purely read-only reusable completion`() {
        // The strongest form of the row: every other rule would have said SKIP here. It does not,
        // because NEVER is the only thing that constrains re-execution of durable history.
        assertEquals(ReplayDecision.ABORT, decide(ReplayPolicy.NEVER, setOf(Effect.READ_ONLY), true, OperationStatus.UNSTABLE))
    }

    // --- row 4: RERUN means reuse ------------------------------------------------

    @Test
    fun `row 4 RERUN skips a reusable completion`() {
        assertEquals(
            ReplayDecision.SKIP,
            decide(ReplayPolicy.RERUN, setOf(Effect.WRITES_WORKSPACE), true, OperationStatus.SUCCEEDED),
            "RERUN is reuse regardless of the effect set -- that is what the name does not say",
        )
    }

    @Test
    fun `row 4 an UNSTABLE completion is reusable, so RERUN still skips it`() {
        // UNSTABLE ran to completion: the work is done and the typed output is journalled. Refusing
        // to reuse it re-executes finished work purely because the durable status is not green.
        assertEquals(ReplayDecision.SKIP, decide(ReplayPolicy.RERUN, setOf(Effect.READ_ONLY), true, OperationStatus.UNSTABLE))
    }

    // --- row 5: RERUN without a reusable completion re-executes ------------------

    @Test
    fun `row 5 RERUN re-executes a non-reusable completion`() {
        for (outcome in listOf(OperationStatus.FAILED, OperationStatus.LOST, OperationStatus.ABORTED)) {
            assertEquals(
                ReplayDecision.RERUN,
                decide(ReplayPolicy.RERUN, setOf(Effect.READ_ONLY), true, outcome),
                "$outcome is not a completion, so there is nothing to serve",
            )
        }
    }

    // --- row 6: MEMOIZED with a purely read-only reusable completion ------------

    @Test
    fun `row 6 MEMOIZED skips a purely read-only reusable completion`() {
        assertEquals(
            ReplayDecision.SKIP,
            decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), true, OperationStatus.SUCCEEDED),
        )
    }

    // --- row 7: MEMOIZED without reuse re-executes -------------------------------

    @Test
    fun `row 7 MEMOIZED re-executes a purely read-only NON-reusable completion`() {
        // The effect set is right and the outcome is not, so the row's third condition fails. This
        // is the case that separates rule 6 from rule 7: nothing about the effect set can rescue it.
        assertEquals(ReplayDecision.RERUN, decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), true, OperationStatus.FAILED))
    }

    @Test
    fun `MEMOIZED cannot memoise when the journal flag and the outcome disagree`() {
        // `hasJournalEntry = true` with a null outcome. `reusable` is false, so nothing is served;
        // the run is re-executed rather than aborted, because the containment and NEVER rows did
        // not match either. A flag that claims an entry the caller cannot name is not authority.
        assertEquals(ReplayDecision.RERUN, decide(ReplayPolicy.MEMOIZED, setOf(Effect.READ_ONLY), true, null))
    }

    // --- row 8: MEMOIZED is PURELY read-only ------------------------------------

    @Test
    fun `row 8 MEMOIZED re-executes an effectful completion`() {
        for (effect in listOf(Effect.EXECUTES_SUBPROCESS, Effect.WRITES_WORKSPACE, Effect.NETWORKS)) {
            assertEquals(
                ReplayDecision.RERUN,
                decide(ReplayPolicy.MEMOIZED, setOf(effect), true, OperationStatus.SUCCEEDED),
                "$effect must not be memoisable, however green the completion is",
            )
        }
    }

    @Test
    fun `row 8 MEMOIZED re-executes a MIXED set even when READ_ONLY is declared`() {
        // THE property of `memoisable`. The descriptor's `effects: List<Effect>` makes mixed sets
        // representable, so `contains(READ_ONLY)` would skip re-writing workspace state. Reading
        // it as "all" is the whole rule.
        val mixed = setOf(Effect.READ_ONLY, Effect.WRITES_WORKSPACE)

        assertEquals(ReplayDecision.RERUN, decide(ReplayPolicy.MEMOIZED, mixed, true, OperationStatus.SUCCEEDED))
    }

    @Test
    fun `MEMOIZED re-executes when the step declared NO effect at all`() {
        // An executor that declared no effect has said nothing about purity. Treating the empty set
        // as read-only would silently memoise the most under-declared steps in the system.
        assertEquals(ReplayDecision.RERUN, decide(ReplayPolicy.MEMOIZED, emptySet(), true, OperationStatus.SUCCEEDED))
    }

    // --- the reuse classification is ONE authority ------------------------------

    @Test
    fun `isReusableCompletion admits exactly SUCCEEDED and UNSTABLE`() {
        // Written as an exhaustive partition of the enum rather than two positive cases, so ADDING
        // a status cannot land here undecided. Every value has a stated answer.
        val reusable = OperationStatus.entries.filter { it.isReusableCompletion() }
        assertEquals(
            listOf(OperationStatus.SUCCEEDED, OperationStatus.UNSTABLE),
            reusable,
            "a completion is work that finished; every other status must stay non-reusable",
        )
    }

    @Test
    fun `every non-completion status is explicitly refused, by name`() {
        // The same partition stated from the other side. If a status is neither in the list above
        // nor named here, this fails -- which is the property the previous test cannot see.
        val refused = listOf(
            OperationStatus.PENDING,
            OperationStatus.RUNNING,
            OperationStatus.FAILED,
            OperationStatus.ABORTED,
            OperationStatus.DIVERGENT,
            OperationStatus.LOST,
            OperationStatus.FAILED_TIMEOUT,
        )
        for (status in refused) {
            assertFalse(status.isReusableCompletion(), "$status must not be reusable")
        }
        assertEquals(OperationStatus.entries.size, 2 + refused.size, "the partition must cover the enum")
    }

    @Test
    fun `a terminal status is not a completion`() {
        // The distinction the KDoc exists to preserve. Five of the six terminal statuses are
        // terminal because something went wrong; reading "terminal" as "finished" would memoise
        // failures, and a memoised failure is a failure that never gets retried.
        val terminalButNotACompletion = OperationStatus.entries
            .filter { it.isTerminal && !it.isReusableCompletion() }
        assertTrue(
            terminalButNotACompletion.containsAll(
                listOf(
                    OperationStatus.FAILED,
                    OperationStatus.FAILED_TIMEOUT,
                    OperationStatus.ABORTED,
                    OperationStatus.DIVERGENT,
                    OperationStatus.LOST,
                ),
            ),
            "got ${terminalButNotACompletion.map { it.name }}",
        )
    }
}