package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


class ExecutionPlannerTest {

    private fun step(id: String) = StepDescriptor(id = id, type = "sh", configRef = "$id.config")

    private fun definition(
        vararg stepIds: String,
        edges: List<Edge> = emptyList(),
    ): PipelineDefinition = PipelineDefinition(
        id = DefinitionId("p"),
        name = "p",
        version = "0.0.0",
        steps = stepIds.map(::step),
        edges = edges,
    )

    @Test
    fun `no edges plans every step as its own single unit in declaration order`() {
        val plan = ExecutionPlanner.plan(definition("c", "a", "b"))

        assertEquals(
            listOf("c", "a", "b"),
            plan.units.map { (it as ExecutionUnit.Single).step.id },
        )
    }

    @Test
    fun `ordering edges produce sequential single-unit waves`() {
        val plan = ExecutionPlanner.plan(
            definition(
                "deploy", "build", "test",
                edges = listOf(Edge("build", "test", EdgeKind.SEQUENTIAL), Edge("test", "deploy", EdgeKind.SEQUENTIAL)),
            )
        )

        assertEquals(listOf("build", "test", "deploy"), plan.linearSteps.map { it.id })
        assertTrue(plan.units.all { it is ExecutionUnit.Single })
    }

    @Test
    fun `PARALLEL edge opts two siblings into the same concurrent wave`() {
        // build and lint both precede deploy; the PARALLEL edge opts them
        // into co-scheduling. Without it they would stay sequential singles.
        val plan = ExecutionPlanner.plan(
            definition(
                "deploy", "build", "lint",
                edges = listOf(
                    Edge("build", "deploy"),
                    Edge("lint", "deploy"),
                    Edge("build", "lint", EdgeKind.PARALLEL),
                ),
            )
        )

        assertEquals(2, plan.units.size)
        val wave = plan.units.first() as ExecutionUnit.Concurrent
        assertEquals(listOf("build", "lint"), wave.steps.map { it.id }, "wave order is declaration order")
        assertEquals("deploy", (plan.units.last() as ExecutionUnit.Single).step.id)
    }

    @Test
    fun `constraint-free siblings WITHOUT a PARALLEL edge stay sequential singles`() {
        // Concurrency is opt-in: absence of constraints never implies
        // co-scheduling (legacy semantic-order equivalence).
        val plan = ExecutionPlanner.plan(
            definition(
                "deploy", "build", "lint",
                edges = listOf(Edge("build", "deploy"), Edge("lint", "deploy")),
            )
        )

        assertTrue(plan.units.all { it is ExecutionUnit.Single })
        assertEquals(listOf("build", "lint", "deploy"), plan.linearSteps.map { it.id })
    }

    @Test
    fun `PARALLEL edges create no ordering but assert same-wave membership`() {
        val plan = ExecutionPlanner.plan(
            definition(
                "a", "b",
                edges = listOf(Edge("a", "b", EdgeKind.PARALLEL)),
            )
        )

        val wave = plan.units.single() as ExecutionUnit.Concurrent
        assertEquals(listOf("a", "b"), wave.steps.map { it.id })
    }

    @Test
    fun `PARALLEL edge across different waves is contradictory and fails closed`() {
        // a → mid → b ordering chain already separates a and b; declaring
        // them PARALLEL contradicts those constraints.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ExecutionPlanner.plan(
                definition(
                    "a", "b", "mid",
                    edges = listOf(
                        Edge("a", "mid", EdgeKind.SEQUENTIAL),
                        Edge("mid", "b", EdgeKind.SEQUENTIAL),
                        Edge("a", "b", EdgeKind.PARALLEL),
                    ),
                )
            )
        }

        assertTrue(ex.message!!.contains("contradictory"))
    }

    @Test
    fun `edge referencing unknown step id fails closed`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ExecutionPlanner.plan(
                definition("build", edges = listOf(Edge("build", "ghost")))
            )
        }

        assertTrue(ex.message!!.contains("ghost"))
    }

    @Test
    fun `cycle among ordering edges fails closed and names the stuck steps`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ExecutionPlanner.plan(
                definition(
                    "a", "b", "c", "standalone",
                    edges = listOf(Edge("a", "b"), Edge("b", "c"), Edge("c", "a")),
                )
            )
        }

        val message = ex.message!!
        assertTrue(message.contains("cycle"), message)
        assertTrue(!message.contains("standalone"), "standalone is unconstrained, not stuck: $message")
    }

    @Test
    fun `same definition always plans identically`() {
        val definition = definition(
            "a", "b", "c", "d", "deploy",
            edges = listOf(Edge("a", "c"), Edge("b", "d"), Edge("c", "deploy"), Edge("d", "deploy")),
        )

        val first = ExecutionPlanner.plan(definition)
        repeat(10) {
            assertEquals(first, ExecutionPlanner.plan(definition))
        }
    }

    @Test
    fun `duplicate ordering edges do not corrupt in-degree counting`() {
        val plan = ExecutionPlanner.plan(
            definition(
                "build", "test",
                edges = listOf(Edge("build", "test"), Edge("build", "test")),
            )
        )

        assertEquals(listOf("build", "test"), plan.linearSteps.map { it.id })
    }

    @Test
    fun `linearSteps flattens waves back to deterministic declaration order`() {
        // Declaration order: deploy(0), lint(1), build(2). Without a
        // PARALLEL edge the two siblings stay sequential singles in
        // declaration order.
        val plan = ExecutionPlanner.plan(
            definition(
                "deploy", "lint", "build",
                edges = listOf(Edge("build", "deploy"), Edge("lint", "deploy")),
            )
        )

        assertEquals(listOf("lint", "build", "deploy"), plan.linearSteps.map { it.id })
    }

    @Test
    fun `CONDITIONAL edges still contribute ordering at the M2 surface`() {
        val plan = ExecutionPlanner.plan(
            definition(
                "a", "b",
                edges = listOf(Edge("a", "b", EdgeKind.CONDITIONAL)),
            )
        )

        assertEquals(listOf("a", "b"), plan.linearSteps.map { it.id })
        assertTrue(plan.units.all { it is ExecutionUnit.Single })
    }
}
