package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The parallel lineage an operation id carries.
 *
 * ## The guarantee
 *
 * The lineage is the ordered list of the branch frames the operation runs inside, and it is what
 * says WHICH branch an operation belongs to. It is read from the body path, because the body
 * path is where the branch frame lives: the parallel engine writes `"b{branchIndex}:branch"` as
 * the FIRST segment of a branch step's path, and that segment is the branch the step runs in.
 *
 * It used to be read as `"{branchIndex}:branch"` — a shape nobody writes — so the lineage came
 * back EMPTY for every branch the product ran. An empty lineage is the LINEAR lane, and the
 * lane is what decides whether two operations are the same owner: a `lock` held by one branch of
 * a `parallel` was re-entered by its sibling, and the mutual exclusion the lock exists for was
 * gone in exactly the situation where it is relied upon.
 *
 * Pure: an operation id is a value, and nothing here touches a disk, a clock or a process.
 */
class OpIdParallelLineageTest {

    /**
     * The frame the parallel engine writes.
     */
    private fun branchFrame(branchIndex: Int): BlockSegment =
        BlockSegment("${OpId.BRANCH_FRAME_PREFIX}$branchIndex:${OpId.PARALLEL_BRANCH_SEGMENT}")

    private fun lineageOf(bodyPath: List<BlockSegment>): List<Int> =
        OpId(runId = "run-7", stageIndex = 0, stepIndex = 0, bodyPath = bodyPath).parallelLineage

    @Test
    @DisplayName("a step of a branch names the branch it runs in")
    fun aStepOfABranchNamesTheBranchItRunsIn() {
        assertEquals(
            listOf(0),
            lineageOf(listOf(branchFrame(0), BlockSegment(0, PluginStepId("core.sh")))),
            "the branch frame of the body path names the branch",
        )
        assertEquals(
            listOf(1),
            lineageOf(listOf(branchFrame(1), BlockSegment(0, PluginStepId("core.sh")))),
            "the second branch of the same stage is a branch of its own",
        )
    }

    @Test
    @DisplayName("a step outside a branch names no branch")
    fun aStepOutsideABranchNamesNoBranch() {
        assertEquals(
            emptyList<Int>(),
            lineageOf(emptyList()),
            "a linear operation runs in no branch",
        )
        assertEquals(
            emptyList<Int>(),
            lineageOf(listOf(BlockSegment(0, PluginStepId("core.sh")))),
            "a child of a body runs in no branch when the body is not in one",
        )
        assertEquals(
            emptyList<Int>(),
            lineageOf(listOf(BlockSegment("n:closure"))),
            "a named body is not a branch",
        )
    }

    @Test
    @DisplayName("a branch inside a branch names both, outermost first")
    fun aBranchInsideABranchNamesBothOutermostFirst() {
        assertEquals(
            listOf(0, 1),
            lineageOf(
                listOf(
                    branchFrame(0),
                    BlockSegment(0, PluginStepId("core.parallel")),
                    branchFrame(1),
                    BlockSegment(0, PluginStepId("core.sh")),
                ),
            ),
            "the lineage is the ordered list of the frames, outermost first",
        )
    }
}
