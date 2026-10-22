package dev.rubentxu.pipeline.v2.application.console

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A filter over stages and steps has to decide with the operation id and
 * nothing else.
 *
 * When no journal is reachable there is no list of operations to intersect
 * with, so the filter is honoured where it can be: the operation id the read
 * was already scoped to states its own stage and step, and the selection
 * checks them. That is the whole authority this path has, which is why the
 * reading of those two numbers must not depend on anything else in the id —
 * a body that cannot be read is still the operation of a stage and a step.
 *
 * The other direction matters just as much: an id that could be about another
 * stage must not be selected as this stage's, because a filter that shows too
 * much reads as a claim about the run.
 *
 * Pure: a selection is a value, and nothing here touches a disk, a clock or a
 * process.
 */
class OperationSelectionTest {

    @Test
    @DisplayName("the operation of the required stage and step is selected")
    fun theOperationOfTheRequiredStageAndStepIsSelected() {
        val selection = OperationSelection.IndexOnly(requiredStage = 0, requiredStep = 3)

        assertTrue(selection.includes("run-7-s0-3"), "the operation of that stage and step belongs to the filter")
        assertFalse(selection.includes("run-7-s1-3"), "an operation of another stage does not")
        assertFalse(selection.includes("run-7-s0-2"), "an operation of another step does not")
    }

    @Test
    @DisplayName("an operation whose body cannot be read is still selected by its indices")
    fun anOperationWhoseBodyCannotBeReadIsStillSelectedByItsIndices() {
        val selection = OperationSelection.IndexOnly(requiredStage = 0, requiredStep = 3)

        assertTrue(
            selection.includes("run-7-s0-3-bp2-0:core-with-dash-n:closure"),
            "the stage and the step are readable even when the body is not",
        )
    }

    @Test
    @DisplayName("an operation id that names two operations is not selected")
    fun anOperationIdThatNamesTwoOperationsIsNotSelected() {
        val selection = OperationSelection.IndexOnly(requiredStage = 0, requiredStep = 3)

        assertFalse(
            selection.includes("run-7-s0-3-bp1-0:b-s1-2"),
            "an id that could be about another stage is not this stage's operation",
        )
    }
}
