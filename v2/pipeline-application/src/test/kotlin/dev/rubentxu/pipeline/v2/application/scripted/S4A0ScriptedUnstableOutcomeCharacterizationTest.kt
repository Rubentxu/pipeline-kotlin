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
    fun `CHARACTERIZED - the invoker's result type cannot carry an unstable marker either`() {
        // The same loss one level up, and the reason widening the durable enum alone
        // would not be enough: even if the status could say UNSTABLE, this sealed
        // result has only two cases and neither can express it.
        val cases = ScriptedRegistryResult::class.java.declaredClasses
            .map { it.simpleName }
            .toSet()
        assertTrue(
            cases == setOf("Success", "Failed"),
            "CHARACTERIZED: ScriptedRegistryResult is exactly $cases — Success carries an " +
                "encoded output, Failed carries a PipelineFailure. Neither can say 'this " +
                "succeeded but is unstable', so a consumer of the invoker cannot recover the " +
                "marker that StepOutcome.Unstable carried.",
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
