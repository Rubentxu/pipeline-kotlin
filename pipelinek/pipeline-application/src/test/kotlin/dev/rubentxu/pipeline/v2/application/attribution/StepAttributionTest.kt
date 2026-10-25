package dev.rubentxu.pipeline.v2.application.attribution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The decision three projections ask about the same terminal event. Its
 * whole value is that the answer does not depend on which projection asked:
 * a terminal that could belong to several open steps belongs to none of them,
 * and a terminal that could belong to one belongs to that one.
 *
 * The exception is measured, not assumed: the branches of a parallel stage
 * share the stage AND the ordinal, so a terminal that names its branch is
 * the only thing that can tell the branches apart. The name may narrow the
 * candidates the ordinal and the stage selected, and it may never widen them
 * and never discard one that it does not match.
 */
class StepAttributionTest {

    private data class Open(val stageIndex: Int, val stepIndex: Int, val stepName: String)

    private val open = listOf(
        Open(stageIndex = 0, stepIndex = 1, stepName = "build"),
        Open(stageIndex = 1, stepIndex = 1, stepName = "test"),
        Open(stageIndex = 1, stepIndex = 2, stepName = "publish"),
    )

    private fun resolve(stageIndex: Int?, stepIndex: Int, stepName: String? = null): StepAttribution =
        StepAttribution.resolve(open, stageIndex, stepIndex, stepName) {
            OpenStepIdentity(it.stageIndex, it.stepIndex, it.stepName)
        }

    @Test
    fun `a terminal that names its stage matches the open step of that stage`() {
        assertEquals(StepAttribution.Matched(2), resolve(stageIndex = 1, stepIndex = 2))
    }

    @Test
    fun `a terminal without a stage matches the only candidate of its ordinal`() {
        assertEquals(StepAttribution.Matched(2), resolve(stageIndex = null, stepIndex = 2))
    }

    @Test
    fun `a terminal that matches several open steps belongs to none of them`() {
        assertEquals(StepAttribution.Ambiguous(2), resolve(stageIndex = null, stepIndex = 1))
    }

    @Test
    fun `a terminal that matches no open step matches none`() {
        assertEquals(StepAttribution.None, resolve(stageIndex = 0, stepIndex = 2))
        assertEquals(StepAttribution.None, resolve(stageIndex = null, stepIndex = 7))
    }

    @Test
    fun `the matched index is the position of the candidate, not its ordinal`() {
        // Two open steps share the ordinal 1 across stages; the stage is what
        // tells them apart, and the answer is the position of the candidate.
        assertEquals(StepAttribution.Matched(1), resolve(stageIndex = 1, stepIndex = 1))
        assertEquals(StepAttribution.Matched(0), resolve(stageIndex = 0, stepIndex = 1))
    }

    @Test
    fun `the branches of a parallel stage share the ordinal and are told apart by the name`() {
        // Three branches of one parallel stage, each with a single step: the
        // stage and the ordinal are the same for all three, and only the
        // name says which branch a terminal belongs to.
        val branches = listOf(
            Open(stageIndex = 0, stepIndex = 0, stepName = "parallel/branch-a/echo-0"),
            Open(stageIndex = 0, stepIndex = 0, stepName = "parallel/branch-b/echo-0"),
            Open(stageIndex = 0, stepIndex = 0, stepName = "parallel/branch-c/echo-0"),
        )

        val attribution = StepAttribution.resolve(branches, 0, 0, "parallel/branch-b/echo-0") {
            OpenStepIdentity(it.stageIndex, it.stepIndex, it.stepName)
        }

        assertEquals(StepAttribution.Matched(1), attribution)
    }

    @Test
    fun `a name that selects no candidate decides nothing`() {
        // A shell failure names "sh" and the SDK's own failure names "error":
        // names that belong to no step. Obeying one of them would turn a
        // decidable terminal into an undecidable one.
        val branches = listOf(
            Open(stageIndex = 0, stepIndex = 0, stepName = "parallel/branch-a/echo-0"),
            Open(stageIndex = 0, stepIndex = 0, stepName = "parallel/branch-b/echo-0"),
        )

        val byGenericName = StepAttribution.resolve(branches, 0, 0, "sh") {
            OpenStepIdentity(it.stageIndex, it.stepIndex, it.stepName)
        }
        val byBlankName = StepAttribution.resolve(branches, 0, 0, "  ") {
            OpenStepIdentity(it.stageIndex, it.stepIndex, it.stepName)
        }
        val byAbsentName = StepAttribution.resolve(branches, 0, 0, null) {
            OpenStepIdentity(it.stageIndex, it.stepIndex, it.stepName)
        }

        assertEquals(StepAttribution.Ambiguous(2), byGenericName)
        assertEquals(StepAttribution.Ambiguous(2), byBlankName)
        assertEquals(StepAttribution.Ambiguous(2), byAbsentName)
    }

    @Test
    fun `the name never removes the only candidate`() {
        // One candidate, and the terminal names something else: there is
        // nothing to disambiguate, so the candidate is the answer.
        assertEquals(StepAttribution.Matched(0), resolve(stageIndex = 0, stepIndex = 1, stepName = "sh"))
    }
}
