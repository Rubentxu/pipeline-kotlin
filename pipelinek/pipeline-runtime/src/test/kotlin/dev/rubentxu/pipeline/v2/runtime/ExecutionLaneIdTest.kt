package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.runtime.durable.OpId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The lane a step of a parallel branch runs in.
 *
 * ## The guarantee
 *
 * The execution lane is the owner a `lock` is held by, and it decides whether two operations are
 * the same owner: the same lane re-enters the lock it already holds — a nested `lock` must not
 * deadlock against its own hold — and a DIFFERENT lane contends for it, which is the exclusion
 * the lock exists to provide.
 *
 * A step of a parallel branch runs in the lane of THAT branch, so two branches of one stage are
 * two lanes: a `lock` held by one of them is contended by the other. The DSL says so, and the
 * runtime bridge says so, and the lineage that carries the branch is what makes it true.
 *
 * It used to be false: the lineage was read as `"{branchIndex}:branch"`, a shape nobody writes —
 * the parallel engine writes `"b{branchIndex}:branch"` — so the lineage came back empty for every
 * branch, every branch was the linear lane, and the sibling branch RE-ENTERED the lock its
 * sibling held. The mutual exclusion was gone in exactly the situation it is relied upon.
 *
 * Pure: an operation id and a lane are values, and nothing here touches a disk, a clock or a
 * process.
 */
class ExecutionLaneIdTest {

    /**
     * The frame the parallel engine writes as the FIRST segment of a branch step's path.
     */
    private fun branchFrame(branchIndex: Int): BlockSegment =
        BlockSegment("${OpId.BRANCH_FRAME_PREFIX}$branchIndex:${OpId.PARALLEL_BRANCH_SEGMENT}")

    /**
     * The lane of an operation that runs with this body path.
     */
    private fun laneOf(bodyPath: List<BlockSegment>): ExecutionLaneId =
        ExecutionLaneId.of(
            runId = RUN_ID,
            branchLineage = OpId(
                runId = RUN_ID,
                stageIndex = 0,
                stepIndex = 0,
                bodyPath = bodyPath,
            ).parallelLineage,
        )

    @Test
    @DisplayName("two sibling branches are two lanes")
    fun twoSiblingBranchesAreTwoLanes() {
        val first = laneOf(listOf(branchFrame(0), BlockSegment(0, PluginStepId("core.sh"))))
        val second = laneOf(listOf(branchFrame(1), BlockSegment(0, PluginStepId("core.sh"))))

        assertNotEquals(
            first,
            second,
            "a sibling branch is a different owner, so a lock held by one is contended by the other",
        )
        assertEquals(
            ExecutionLaneId("$RUN_ID-b0"),
            first,
            "the lane names the run and the branch it runs in",
        )
        assertEquals(
            ExecutionLaneId("$RUN_ID-b1"),
            second,
            "the lane names the run and the branch it runs in",
        )
    }

    @Test
    @DisplayName("a step outside a branch runs in the run's lane")
    fun aStepOutsideABranchRunsInTheRunsLane() {
        assertEquals(
            ExecutionLaneId(RUN_ID),
            laneOf(emptyList()),
            "a linear step runs in the lane of the run",
        )
        assertEquals(
            ExecutionLaneId(RUN_ID),
            laneOf(listOf(BlockSegment(0, PluginStepId("core.sh")))),
            "a step of a body runs in the lane of the run when the body is not in a branch",
        )
    }

    @Test
    @DisplayName("a nested step of a branch runs in the lane of that branch")
    fun aNestedStepOfABranchRunsInTheLaneOfThatBranch() {
        val branch = laneOf(listOf(branchFrame(0), BlockSegment(0, PluginStepId("core.lock"))))
        val nested = laneOf(
            listOf(
                branchFrame(0),
                BlockSegment(0, PluginStepId("core.lock")),
                BlockSegment(0, PluginStepId("core.sh")),
            ),
        )

        assertEquals(
            branch,
            nested,
            "a lock nested in a branch re-enters the lane of that branch, so it cannot deadlock " +
                "against its own hold",
        )
    }

    @Test
    @DisplayName("the operation id the engine writes names the lane of its branch")
    fun theOperationIdTheEngineWritesNamesTheLaneOfItsBranch() {
        val written = OpId.parse("$RUN_ID-s0-0-bp2-b0:branch-0:core.sh")

        assertEquals(
            listOf(0),
            written?.parallelLineage,
            "the text the parallel engine writes is read back as the branch it runs in",
        )
        assertEquals(
            ExecutionLaneId("$RUN_ID-b0"),
            ExecutionLaneId.of(RUN_ID, written!!.parallelLineage),
            "the lane of a written operation is the lane of its branch",
        )
    }

    private companion object {
        /** The run every operation of this test belongs to. */
        const val RUN_ID = "run-7"
    }
}
