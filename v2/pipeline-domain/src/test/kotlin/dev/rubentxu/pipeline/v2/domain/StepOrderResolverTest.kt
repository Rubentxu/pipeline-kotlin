package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StepOrderResolverTest {

    private fun step(id: String) = StepDescriptor(id = id, type = "sh", configRef = "$id.config")

    @Test
    fun `no edges resolves to declaration order`() {
        val definition = PipelineDefinition(
            id = DefinitionId("p"),
            name = "p",
            version = "0.0.0",
            steps = listOf(step("c"), step("a"), step("b")),
        )

        assertEquals(listOf("c", "a", "b"), StepOrderResolver.resolve(definition).map { it.id })
    }

    @Test
    fun `edges produce topological order regardless of declaration`() {
        val definition = PipelineDefinition(
            id = DefinitionId("p"),
            name = "p",
            version = "0.0.0",
            steps = listOf(step("deploy"), step("build"), step("test")),
            edges = listOf(Edge("build", "test"), Edge("test", "deploy")),
        )

        assertEquals(listOf("build", "test", "deploy"), StepOrderResolver.resolve(definition).map { it.id })
    }

    @Test
    fun `independent components interleave in declaration order deterministically`() {
        // build and lint have no relationship; both precede deploy via edges.
        // build declared before lint (index 1 vs 2), so the deterministic
        // tie-break must always pick build first.
        val definition = PipelineDefinition(
            id = DefinitionId("p"),
            name = "p",
            version = "0.0.0",
            steps = listOf(step("deploy"), step("build"), step("lint")),
            edges = listOf(Edge("build", "deploy"), Edge("lint", "deploy")),
        )

        val order = StepOrderResolver.resolve(definition).map { it.id }
        assertEquals(listOf("build", "lint", "deploy"), order)
    }

    @Test
    fun `same definition always resolves to the same order`() {
        val definition = PipelineDefinition(
            id = DefinitionId("p"),
            name = "p",
            version = "0.0.0",
            steps = listOf(step("a"), step("b"), step("c"), step("d")),
            edges = listOf(Edge("a", "c"), Edge("b", "d")),
        )

        val first = StepOrderResolver.resolve(definition).map { it.id }
        repeat(10) {
            assertEquals(first, StepOrderResolver.resolve(definition).map { it.id })
        }
    }

    @Test
    fun `edge referencing unknown step id fails closed`() {
        val definition = PipelineDefinition(
            id = DefinitionId("p"),
            name = "p",
            version = "0.0.0",
            steps = listOf(step("build")),
            edges = listOf(Edge("build", "ghost")),
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            StepOrderResolver.resolve(definition)
        }
        assertTrue(ex.message!!.contains("ghost"))
    }

    @Test
    fun `cycle among edges fails closed and names the unreachable steps`() {
        val definition = PipelineDefinition(
            id = DefinitionId("p"),
            name = "p",
            version = "0.0.0",
            steps = listOf(step("a"), step("b"), step("c"), step("standalone")),
            edges = listOf(Edge("a", "b"), Edge("b", "c"), Edge("c", "a")),
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            StepOrderResolver.resolve(definition)
        }
        val message = ex.message!!
        assertTrue(message.contains("cycle"), message)
        assertTrue(message.contains("standalone").not(), "standalone is reachable, not stuck: $message")
    }

    @Test
    fun `duplicate edges do not corrupt in-degree counting`() {
        val definition = PipelineDefinition(
            id = DefinitionId("p"),
            name = "p",
            version = "0.0.0",
            steps = listOf(step("build"), step("test")),
            edges = listOf(Edge("build", "test"), Edge("build", "test")),
        )

        assertEquals(listOf("build", "test"), StepOrderResolver.resolve(definition).map { it.id })
    }

    @Test
    fun `PARALLEL and CONDITIONAL edges still resolve to a linear order at the M2 surface`() {
        val definition = PipelineDefinition(
            id = DefinitionId("p"),
            name = "p",
            version = "0.0.0",
            steps = listOf(step("a"), step("b"), step("c")),
            edges = listOf(
                Edge("a", "b", EdgeKind.PARALLEL),
                Edge("b", "c", EdgeKind.CONDITIONAL),
            ),
        )

        assertEquals(listOf("a", "b", "c"), StepOrderResolver.resolve(definition).map { it.id })
    }
}
