package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * An operation id that is read back has to be the one that was written.
 *
 * [OpId.parse] is the only way an operation id that arrived from outside
 * becomes a typed identity again. A reader that reads *part* of an id is
 * worse than one that refuses it: the caller gets an [OpId] that looks right
 * and names something else — another operation, another stage, another step.
 * And the identity of an operation is what the journal is keyed by, so reading
 * a different one is not a cosmetic mistake: it is a wrong answer with the
 * shape of a right one.
 *
 * Two halves, two rows: whatever comes out is the identity that was written,
 * and what cannot be read exactly is refused rather than guessed.
 *
 * Pure and total: an operation id is a value, and nothing here touches a disk,
 * a clock or a process.
 */
class OpIdRejectionTest {

    @Test
    @DisplayName("whatever is read back is the identity that was written")
    fun whateverIsReadBackIsTheIdentityThatWasWritten() {
        val texts = listOf(
            // The shapes the engine writes.
            "run-7-s0-3",
            "run-7-s0-3-b1",
            "run-7-s0-3-bp1-0:core.sh",
            "run-7-s0-3-bp4-0:core.parallel-b1:branch-n:closure-2:core.sh",
            // A segment whose own name carries a hyphen: it is still one segment.
            "run-7-s0-3-bp1-0:core-with-dash",
            // Two segments, one of them carrying a hyphen: the cut is ambiguous.
            "run-7-s0-3-bp2-0:core-with-dash-n:closure",
            // A text that reads as a root with a body, or as a root alone.
            "run-7-s0-3-bp1-0:b-s1-2",
            // Texts that are not operation ids at all.
            "not-an-operation-id",
            "run-7-s0",
        )

        for (text in texts) {
            val parsed = OpId.parse(text)
            if (parsed != null) {
                assertEquals(
                    text,
                    parsed.format(),
                    "the identity read back must be the one that was written",
                )
            }
        }
    }

    @Test
    @DisplayName("a text that cannot be read exactly is refused")
    fun aTextThatCannotBeReadExactlyIsRefused() {
        // Two segments, one of them carrying a hyphen: the text can be cut in
        // more than one way, and each cut is a different identity.
        assertNull(
            OpId.parse("run-7-s0-3-bp2-0:core-with-dash-n:closure"),
            "a body that can be cut in more than one way is not read",
        )

        // The same text reads as a root with a body, or as a root whose run
        // identifier ends in something that looks like one.
        assertNull(
            OpId.parse("run-7-s0-3-bp1-0:b-s1-2"),
            "a text that names two operations is not read as one of them",
        )
    }

    @Test
    @DisplayName("the stage and the step are readable even when the body is not")
    fun theStageAndTheStepAreReadableEvenWhenTheBodyIsNot() {
        val indices = requireNotNull(
            OpId.parseIndices("run-7-s0-3-bp2-0:core-with-dash-n:closure"),
        ) { "the body being unreadable does not make the stage and the step unreadable" }
        assertEquals(0, indices.stageIndex)
        assertEquals(3, indices.stepIndex)
    }
}
