package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The structural derivation behind `core.lock`'s re-entrancy decision
 * (RP6-A / WU-091).
 *
 * `OpId.parallelLineage` is what the capability bridge turns into an
 * [dev.rubentxu.pipeline.v2.application.ExecutionLaneId]. If it is wrong, the
 * lock silently loses the mutual exclusion it exists to provide, and no
 * coordinator-level test would necessarily notice — a wrong lane is a wrong
 * ANSWER, not a crash.
 *
 * So these rows are about the derivation being a pure function of journalled
 * structure, and about nested parallel being representable without a type change.
 */
class ExecutionLaneDerivationTest {

    private fun branchSegment(index: Int) = BlockSegment(index, PluginStepId(OpId.PARALLEL_BRANCH_SEGMENT))

    private fun otherSegment(index: Int) = BlockSegment(index, PluginStepId("core.lock"))

    @Test
    fun `a linear path has an empty lineage`() {
        assertEquals(emptyList<Int>(), OpId("r", stageIndex = 0, stepIndex = 3).parallelLineage)
    }

    @Test
    fun `a single branch contributes one level`() {
        val op = OpId("r", stageIndex = 0, stepIndex = 1, bodyPath = listOf(branchSegment(0)))
        assertEquals(listOf(0), op.parallelLineage)
    }

    /**
     * The case that decides the shape. Nested parallel produces a lineage of
     * length 2, NOT a single overwritten index — which is why the lane is
     * derived from a list rather than from `branchIndex`.
     */
    @Test
    fun `nested parallel widens the lineage instead of overwriting it`() {
        val op = OpId(
            runId = "r",
            stageIndex = 0,
            stepIndex = 1,
            bodyPath = listOf(branchSegment(0), branchSegment(1)),
        )
        assertEquals(
            listOf(0, 1),
            op.parallelLineage,
            "two nesting levels must be two entries; a single index would make the outer " +
                "and inner lanes indistinguishable",
        )
    }

    @Test
    fun `non-branch segments are not part of the lineage`() {
        // The lane must survive descending INTO a lock body: those segments are
        // block nesting, not parallel frames, and including them would make every
        // nested lock a different lane and break legitimate re-entrancy.
        val op = OpId(
            runId = "r",
            stageIndex = 0,
            stepIndex = 1,
            bodyPath = listOf(branchSegment(0), otherSegment(0)),
        )
        assertEquals(
            listOf(0),
            op.parallelLineage,
            "descending into a block body must not change the lane, or a nested lock would " +
                "contend with its own outer lock",
        )
    }

    @Test
    fun `the derivation is pure and stable`() {
        val op = OpId("r", 0, 1, bodyPath = listOf(branchSegment(0)))
        val a = dev.rubentxu.pipeline.v2.application.ExecutionLaneId.of(op.runId, op.parallelLineage)
        val b = dev.rubentxu.pipeline.v2.application.ExecutionLaneId.of(op.runId, op.parallelLineage)
        assertEquals(a, b, "the same journalled structure must always yield the same lane")
    }

    @Test
    fun `sibling branches are different lanes`() {
        val laneA = dev.rubentxu.pipeline.v2.application.ExecutionLaneId.of("r", listOf(0))
        val laneB = dev.rubentxu.pipeline.v2.application.ExecutionLaneId.of("r", listOf(1))
        assertNotEquals(laneA, laneB, "two parallel branches must not share a lane")
    }

    @Test
    fun `different runs are different lanes even with the same lineage`() {
        val here = dev.rubentxu.pipeline.v2.application.ExecutionLaneId.of("run-a", listOf(0))
        val there = dev.rubentxu.pipeline.v2.application.ExecutionLaneId.of("run-b", listOf(0))
        assertNotEquals(here, there)
    }

    @Test
    fun `a linear lane is not confused with a branched one`() {
        val linear = dev.rubentxu.pipeline.v2.application.ExecutionLaneId.of("r", emptyList())
        val branched = dev.rubentxu.pipeline.v2.application.ExecutionLaneId.of("r", listOf(0))
        assertNotEquals(
            linear,
            branched,
            "the run id must not be a prefix that makes 'r' and 'r-b0' collide in a way " +
                "that reads as the same owner",
        )
        assertTrue(branched.value.endsWith("-b0"))
    }
}
