package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.console.OperationSelection
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A filtered console shows the frames of the operations that were asked for, and no others.
 *
 * The filter reads the operation out of the frame's stream id, and the operation is the whole
 * middle of that id: it may span several segments. So the id is read from its two ends — the
 * run first, the channel last — and what is left is the operation, whole.
 *
 * Two wrong answers are available to a filter that decides where the operation ends by
 * itself, and both of them show an operation nobody asked for:
 *
 *  - keeping the first segment of a composite id names an operation that does not exist, so
 *    the frames of the real operation disappear from a filtered read, and the frames of a
 *    fragment appear in their place;
 *  - comparing one segment against the selection admits an operation whose id merely
 *    CONTAINS the selected one.
 *
 * Pure: a selection is a value, and every id here is a string. Nothing touches a disk, a clock
 * or a process.
 */
class MainConsoleCliSelectionTest {

    @Test
    fun `the operation of a stream is selected when the selection names it`() {
        val selection = OperationSelection.Operations(setOf("run-7-s0-0"))

        assertTrue(
            MainConsoleCli.selectionSelects(selection, "run-7/run-7-s0-0/stdout"),
            "the frames of the selected operation belong to the filtered read",
        )
    }

    @Test
    fun `an operation that spans several segments is selected by its whole id`() {
        val selection = OperationSelection.Operations(setOf("stage/step/inner"))

        assertTrue(
            MainConsoleCli.selectionSelects(selection, "run-7/stage/step/inner/stdout"),
            "the operation is the whole middle of the id, separators included",
        )
    }

    @Test
    fun `a piece of an operation id is not the operation`() {
        val selection = OperationSelection.Operations(setOf("stage"))

        assertFalse(
            MainConsoleCli.selectionSelects(selection, "run-7/stage/step/inner/stdout"),
            "the first segment of a composite id is not an operation of its own",
        )
    }

    @Test
    fun `an operation that contains another is selected by its whole id`() {
        val selection = OperationSelection.Operations(setOf("other/foo"))

        assertTrue(
            MainConsoleCli.selectionSelects(selection, "run-7/other/foo/stdout"),
            "the operation is named whole, so a selection that names it whole finds it",
        )
    }

    @Test
    fun `an operation whose id contains another is not that other`() {
        val selection = OperationSelection.Operations(setOf("other"))

        assertFalse(
            MainConsoleCli.selectionSelects(selection, "run-7/other/foo/stdout"),
            "the frames of `other/foo` are not the frames of `other`",
        )
    }

    @Test
    fun `a stream that names no operation is not selected`() {
        val selection = OperationSelection.Operations(setOf("run-7-s0-0"))

        assertFalse(
            MainConsoleCli.selectionSelects(selection, "run-7/run-7-s0-0/transcript"),
            "a stream without a channel carries no operation to select",
        )
        assertFalse(
            MainConsoleCli.selectionSelects(selection, "run-7"),
            "a stream too short to name an operation carries none either",
        )
    }

    @Test
    fun `an unfiltered read admits every frame`() {
        assertTrue(
            MainConsoleCli.selectionSelects(OperationSelection.AllOperations, "run-7/other/foo/stdout"),
            "a read with no filter has nothing to decide",
        )
    }
}
