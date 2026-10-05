package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S4-A0 — characterization of the `Unstable` outcome through the durable
 * scripted registry invoker.
 *
 * ## The question
 *
 * `StepOutcome` has three cases and one of them exists deliberately:
 *
 * ```
 * data object Unstable   // "completed but with a non-fatal warning (e.g.
 *                        //  warnError matched a known pattern and the script
 *                        //  returned 0). Does NOT carry a PipelineFailure."
 * ```
 *
 * It is produced for real: `CoreShellStep` derives it from an UNSTABLE marker,
 * and `CoreEmitEventStep` and `CoreMilestoneStep` return it directly. The
 * scripted frontend then consumes it — `MainScriptedSupport` maps
 * `StepOutcome.Unstable` to `RunOutcome.Unstable`, so a run's outcome can be
 * Unstable.
 *
 * So the marker is real, consumed, and load-bearing. This suite characterizes
 * whether it survives the durable scripted registry seam.
 *
 * ## What is proven, and what is not
 *
 * Proven here, structurally and without fixtures: the invoker's own result type
 * and the durable status vocabulary have no member that can represent an
 * unstable outcome, so the marker cannot cross that seam.
 *
 * NOT proven here: that a resumed RUN reports a different outcome from the
 * original. That depends on how `ScriptedFrontendRunner` aggregates the
 * per-call results, which this suite does not trace end to end. It is recorded
 * as the open question S4-C must answer, not asserted as a defect.
 *
 * ## S4-D2 — this file is half characterization, half non-regression
 *
 * The defect measured here is CLOSED by S4-D2, and the history is kept rather than rewritten:
 *
 * ```text
 * BEFORE D2
 *   Unstable was representable in the ADTs, but it was LOST in scripted.
 *   Two propagation losses: the invoker collapsed it into Success(encoded),
 *   and runBody discarded the result and hardcoded Success.
 *
 * RESOLVED BY D2
 *   The representation did NOT change. What was repaired is PROPAGATION:
 *   the outcome is derived from the typed carrier (outcomeOf) and carried
 *   beside the value, and the frontend reduces with RunOutcomeReducer.
 * ```
 *
 * The test that characterised the ADT's narrowness still passes — for a different reason, now
 * stated in its assertion message. The end-to-end non-regression lives in
 * `S4D2ScriptedUnstablePreservationTest`.
 *
 * ## P1 — this file's first row also changed meaning, and says so
 *
 * The first row characterised a gap that was REAL and has now been closed by P1 of the Runtime
 * Observation Contract Closure: `OperationStatus` could not represent an unstable outcome, so the
 * durable layer collapsed that fact three different ways for the same condition. P1 widened the
 * vocabulary with `UNSTABLE` and unified the three sites.
 *
 * Per the Harness Fidelity Law, characterisation that measures a closed defect becomes a
 * non-regression test, and that transition is stated in the assertion message rather than made by
 * quietly rewriting what is expected. The row is now named
 * `NON-REGRESSION (was CHARACTERIZED) - the durable status vocabulary CAN represent unstable` and
 * asserts the opposite, for the same reason: if it goes red, the fact is being lost again.
 *
 * The second and third rows are untouched. The narrowness they describe is `ScriptedRegistryResult`
 * (Success/Failed) and the reality of `StepOutcome.Unstable` as a produced value — neither is
 * affected by the durable vocabulary, and widening that ADT would have duplicated a fact the typed
 * carrier already owns.
 */
class S4A0ScriptedUnstableOutcomeCharacterizationTest {

    @Test
    fun `NON-REGRESSION (was CHARACTERIZED) - the durable status vocabulary CAN represent unstable`() {
        // TRANSITION RECORD — this row changed meaning on purpose when P1 of the Runtime
        // Observation Contract Closure landed, and the change is stated here rather than made
        // silently in the expectation.
        //
        //   BEFORE P1
        //     CHARACTERIZED: `OperationStatus` had no member that could represent an unstable
        //     outcome, so any step producing `StepOutcome.Unstable` had to either lose the marker
        //     or widen the durable vocabulary. It compiled, and the durable layer collapsed the
        //     fact three different ways for the same condition — `FAILED` through `StepOutcome`,
        //     `ABORTED` through `BranchTerminal`, and a persisted `FAILED` through the retry
        //     engine — so a child row and the aggregate row of one branch disagreed about what
        //     had happened.
        //
        //   RESOLVED BY P1
        //     The vocabulary was WIDENED, which is the option the item's own rule requires: a
        //     property that needs a new carrier extends the algebra that owns it. `UNSTABLE` is
        //     terminal and is not a poll failure, and all three sites now persist it, so a
        //     declared-unstable run reads back the same fact it reported on the way in.
        //
        // The `ScriptedRegistryInvoker` arm this row used to name — `is StepOutcome.Unstable ->
        // OperationStatus.SUCCEEDED` — is NOT a fourth site and is not what P1 changed: ADR-0103
        // R1-E already removed the invoker's `when (existing.status)` table, and the invoker now
        // interprets the closed `InvocationReconciliation` ADT instead of a durable status. The
        // prose was simply left describing a state the code had already left.
        val statuses = OperationStatus.entries.map { it.name }.toSet()
        assertTrue(
            "UNSTABLE" in statuses,
            "RESOLVED BY P1: OperationStatus can now represent an unstable outcome, so a step " +
                "producing StepOutcome.Unstable persists the fact instead of losing it. This row " +
                "characterised the ABSENCE of the member and asserted it; the defect is closed, so " +
                "it is now a non-regression that pins the presence. If it goes RED again, an " +
                "unstable run is being collapsed into a different terminal somewhere, and the " +
                "three sites that do it are in P1UnstableSingleDurableStatusTest " +
                "(behavioural, reachable authority) and UnstableSingleDurableStatusFitnessTest " +
                "(the two private ones). The available set is $statuses.",
        )
    }

    @Test
    fun `CHARACTERIZED then RESOLVED - the invoker result stays narrow on purpose, and the outcome moved`() {
        // TRANSITION RECORD — this test changed meaning on purpose when S4-D2 landed, and the
        // change is stated here rather than made silently in the expectation.
        //
        //   BEFORE D2
        //     Unstable WAS representable in the ADTs, but it was LOST in scripted. The invoker
        //     returned a bare value and the frontend hardcoded Success, so the marker never
        //     reached the run.
        //
        //   RESOLVED BY D2
        //     The representation did NOT change. `ScriptedRegistryResult` is still exactly
        //     {Success, Failed} — widening it with an `Unstable` case would have DUPLICATED a
        //     semantic fact the typed carrier already owns. What was repaired is PROPAGATION:
        //     `ScriptedTypedResult.from(value)` now derives the outcome from the carrier via
        //     `outcomeOf`, and the frontend reduces the recorded outcomes with `RunOutcomeReducer`.
        //
        // The second half of this test is the non-regression side: the same narrow ADT is now
        // correct BECAUSE the outcome travels beside the value instead of inside the ADT.
        val cases = ScriptedRegistryResult::class.java.declaredClasses
            .map { it.simpleName }
            .toSet()
        assertTrue(
            cases == setOf("Success", "Failed"),
            "RESOLVED BY D2: ScriptedRegistryResult is still exactly $cases, and that is now " +
                "deliberate. It answers 'did the durable invocation deliver a usable value?', " +
                "not 'was that value unstable?'. An Unstable case here would be a second " +
                "authority able to disagree with the typed carrier. The outcome travels in " +
                "ScriptedTypedResult, which derives it with outcomeOf and cannot be built with " +
                "a value and a disagreeing outcome.",
        )
    }

    @Test
    fun `CHARACTERIZED - the marker is real, not a theoretical case`() {
        // Control against the two tests above being read as "unstable does not exist".
        assertTrue(
            StepOutcome.Unstable::class.objectInstance != null,
            "StepOutcome.Unstable is a real case, produced by CoreShellStep's UNSTABLE marker " +
                "and returned directly by CoreEmitEventStep and CoreMilestoneStep.",
        )
    }
}
