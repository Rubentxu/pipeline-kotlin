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
 */
class S4A0ScriptedUnstableOutcomeCharacterizationTest {

    @Test
    fun `CHARACTERIZED - the durable status vocabulary cannot represent an unstable outcome`() {
        val statuses = OperationStatus.entries.map { it.name }.toSet()
        assertTrue(
            "UNSTABLE" !in statuses,
            "CHARACTERIZED: OperationStatus has no member that can represent an unstable " +
                "outcome, so any step producing StepOutcome.Unstable must either lose the " +
                "marker or widen the durable vocabulary. ScriptedRegistryInvoker chooses to " +
                "lose it: `is StepOutcome.Unstable -> OperationStatus.SUCCEEDED`. The " +
                "available set is $statuses.",
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
