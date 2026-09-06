package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for CompiledExecutionPlanner with Block units (EM-4).
 */
class CompiledExecutionPlannerTest {

    private fun makeOpaqueStepNode(id: String, pluginStepId: String): OpaqueStepNode = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId(pluginStepId),
        payload = VersionedStepPayload("dsl-v1", """{"command":"echo test"}"""),
    )

    private fun makeBlockStepNode(
        id: String,
        pluginStepId: String,
        body: List<StepNode> = emptyList(),
    ): BlockStepNode = BlockStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId(pluginStepId),
        payload = VersionedStepPayload("dsl-v1", "{}"),
        body = body,
    )

    private fun makePipeline(vararg steps: StepNode): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("test-pipeline"),
        source = SourceDescriptor("test.kts", Digest("abc123")),
        stages = listOf(
            StageNode(
                id = StageId("test-stage"),
                name = "Test Stage",
                body = StageBody.Steps(steps.toList()),
            )
        ),
        pluginLockDigest = Digest("lock"),
    )

    @Test
    fun `BlockStepNode emits Block unit with subPlan`() {
        val block = makeBlockStepNode(
            id = "catch-block",
            pluginStepId = "core.catchError",
            body = listOf(
                makeOpaqueStepNode("sh-a", "core.sh"),
                makeOpaqueStepNode("sh-b", "core.sh"),
            )
        )

        val pipeline = makePipeline(block)
        val plan = CompiledExecutionPlanner.plan(pipeline)

        assertEquals(1, plan.units.size)
        val blockUnit = plan.units[0] as? CompiledExecutionUnit.Block
        assertNotNull(blockUnit, "Should be CompiledExecutionUnit.Block")
        val bu = blockUnit!!

        assertEquals(block.id, bu.block.id)

        // Body plan should have two Single units
        assertEquals(2, bu.bodyPlan.units.size)
        assertTrue(bu.bodyPlan.units[0] is CompiledExecutionUnit.Single)
        assertTrue(bu.bodyPlan.units[1] is CompiledExecutionUnit.Single)
    }

    @Test
    fun `nested BlockStepNode emits Block with nested Block`() {
        val innerBlock = makeBlockStepNode(
            id = "inner-dir",
            pluginStepId = "core.dir",
            body = listOf(makeOpaqueStepNode("sh-c", "core.sh"))
        )

        val outerBlock = makeBlockStepNode(
            id = "outer-catch",
            pluginStepId = "core.catchError",
            body = listOf(
                makeOpaqueStepNode("sh-a", "core.sh"),
                makeOpaqueStepNode("sh-b", "core.sh"),
                innerBlock,
            )
        )

        val pipeline = makePipeline(outerBlock)
        val plan = CompiledExecutionPlanner.plan(pipeline)

        assertEquals(1, plan.units.size)
        val outerBlockUnit = plan.units[0] as? CompiledExecutionUnit.Block
        assertNotNull(outerBlockUnit)
        val obu = outerBlockUnit!!

        // Outer body plan: 3 units (Single, Single, Block)
        assertEquals(3, obu.bodyPlan.units.size)
        assertTrue(obu.bodyPlan.units[0] is CompiledExecutionUnit.Single)
        assertTrue(obu.bodyPlan.units[1] is CompiledExecutionUnit.Single)

        val innerBlockUnit = obu.bodyPlan.units[2] as? CompiledExecutionUnit.Block
        assertNotNull(innerBlockUnit, "Inner block should be CompiledExecutionUnit.Block")
        val ibu = innerBlockUnit!!

        assertEquals(innerBlock.id, ibu.block.id)

        // Inner body plan: 1 Single
        assertEquals(1, ibu.bodyPlan.units.size)
        assertTrue(ibu.bodyPlan.units[0] is CompiledExecutionUnit.Single)
    }

    @Test
    fun `mixed steps and blocks plan correctly`() {
        val block = makeBlockStepNode(
            id = "catch-block",
            pluginStepId = "core.catchError",
            body = listOf(makeOpaqueStepNode("sh-a", "core.sh")),
        )

        val pipeline = makePipeline(
            makeOpaqueStepNode("sh-1", "core.sh"),
            block,
            makeOpaqueStepNode("sh-2", "core.echo"),
        )

        val plan = CompiledExecutionPlanner.plan(pipeline)

        assertEquals(3, plan.units.size)
        assertTrue(plan.units[0] is CompiledExecutionUnit.Single)
        assertTrue(plan.units[1] is CompiledExecutionUnit.Block)
        assertTrue(plan.units[2] is CompiledExecutionUnit.Single)
    }

    @Test
    fun `parallel branch cardinality rule unchanged`() {
        // Verify the parallel-cardinality rule is preserved
        val parallelPipeline = CompiledPipeline(
            id = DefinitionId("parallel-pipeline"),
            source = SourceDescriptor("test.kts", Digest("abc123")),
            stages = listOf(
                StageNode(
                    id = StageId("parallel-stage"),
                    name = "Parallel Stage",
                    body = StageBody.Parallel(
                        listOf(
                            StageNode(
                                id = StageId("branch-1"),
                                name = "Branch 1",
                                body = StageBody.Steps(listOf(makeOpaqueStepNode("sh-1", "core.sh")))
                            ),
                            StageNode(
                                id = StageId("branch-2"),
                                name = "Branch 2",
                                body = StageBody.Steps(listOf(makeOpaqueStepNode("sh-2", "core.sh")))
                            ),
                        )
                    ),
                )
            ),
            pluginLockDigest = Digest("lock"),
        )

        val plan = CompiledExecutionPlanner.plan(parallelPipeline)

        assertEquals(1, plan.units.size)
        assertTrue(plan.units[0] is CompiledExecutionUnit.Concurrent)
        val concurrent = plan.units[0] as CompiledExecutionUnit.Concurrent
        assertEquals(2, concurrent.steps.size)
    }
}
