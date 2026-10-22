package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * An operation id has to survive its own text form.
 *
 * [OpId.format] is the canonical spelling of an operation identity, and
 * [OpId.parse] is the only way an operation id that arrived from outside —
 * from the console, from a journal, from a hand-written selector — becomes a
 * typed identity again. A parser that quietly drops part of the identity is
 * worse than one that refuses it: the caller gets an `OpId` that looks right
 * and names something else.
 *
 * The body path is the part that can be dropped. It is a list of segments, and
 * the engine writes three shapes of segment:
 *
 *  - `"{index}:{pluginStepId}"` — a child step, addressed by its position;
 *  - `"b{branchIndex}:{key}"` — a parallel branch frame;
 *  - `"n:{name}"` — a named body.
 *
 * Only the first shape starts with a number. The parser used to accept a
 * segment only when its first field parsed as an integer, so the other two
 * shapes were discarded on the way back in — silently, because the segments
 * were dropped rather than refused.
 *
 * Pure and total: an operation id is a value, and nothing here touches a disk,
 * a clock or a process.
 */
class OpIdRoundTripTest {

    private fun roundTrip(bodyPath: List<BlockSegment>) {
        val original = OpId(
            runId = "run-7",
            stageIndex = 0,
            stepIndex = 3,
            branchIndex = null,
            bodyPath = bodyPath,
        )
        val text = original.format()
        val parsed = OpId.parse(text)

        assertEquals(
            original,
            parsed,
            "the parsed identity must be the one that was written: " +
                "the text form was '$text'",
        )
    }

    @Test
    @DisplayName("an addressed child segment survives the round trip")
    fun anAddressedChildSegmentSurvivesTheRoundTrip() {
        roundTrip(listOf(BlockSegment(0, PluginStepId("core.sh"))))
    }

    @Test
    @DisplayName("a parallel branch segment survives the round trip")
    fun aParallelBranchSegmentSurvivesTheRoundTrip() {
        roundTrip(listOf(BlockSegment("b0:branch")))
    }

    @Test
    @DisplayName("a named body segment survives the round trip")
    fun aNamedBodySegmentSurvivesTheRoundTrip() {
        roundTrip(listOf(BlockSegment("n:closure")))
    }

    @Test
    @DisplayName("a mixed body path survives the round trip")
    fun aMixedBodyPathSurvivesTheRoundTrip() {
        roundTrip(
            listOf(
                BlockSegment(0, PluginStepId("core.parallel")),
                BlockSegment("b1:branch"),
                BlockSegment("n:closure"),
                BlockSegment(2, PluginStepId("core.sh")),
            ),
        )
    }
}
