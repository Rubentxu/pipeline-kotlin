package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PipelineDefinitionTest {

    @Test
    fun `step lookup returns the matching StepDescriptor`() {
        val steps = listOf(
            StepDescriptor(stepId = "build", name = "sh", configRef = "build.config"),
            StepDescriptor(stepId = "test", name = "sh", configRef = "test.config"),
        )
        val def = PipelineDefinition(
            id = DefinitionId("hello"),
            name = "hello",
            version = "0.0.0",
            steps = steps,
        )

        assertEquals(steps[0], def.step("build"))
        assertEquals(steps[1], def.step("test"))
    }

    @Test
    fun `step lookup returns null for unknown ids`() {
        val def = PipelineDefinition(
            id = DefinitionId("hello"),
            name = "hello",
            version = "0.0.0",
        )

        assertEquals(null, def.step("missing"))
    }

    @Test
    fun `duplicate step ids are rejected at construction time`() {
        val ex = assertThrows<IllegalArgumentException> {
            PipelineDefinition(
                id = DefinitionId("hello"),
                name = "hello",
                version = "0.0.0",
                steps = listOf(
                    StepDescriptor(stepId = "build", name = "sh", configRef = "build.config"),
                    StepDescriptor(stepId = "build", name = "sh", configRef = "build.config"),
                ),
            )
        }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("build"))
    }

    @Test
    fun `blank name and version are rejected`() {
        assertThrows<IllegalArgumentException> {
            PipelineDefinition(id = DefinitionId("hello"), name = "", version = "0.0.0")
        }
        assertThrows<IllegalArgumentException> {
            PipelineDefinition(id = DefinitionId("hello"), name = "hello", version = "  ")
        }
    }

    @Test
    fun `default values produce a minimal but valid definition`() {
        val def = PipelineDefinition(
            id = DefinitionId("hello"),
            name = "hello",
            version = "0.0.0",
        )

        assertEquals(emptyList<StepDescriptor>(), def.steps)
        assertEquals(emptyList<Edge>(), def.edges)
        assertEquals(emptyList<Stage>(), def.stages)
    }
}

class EdgeTest {

    @Test
    fun `default edge kind is SEQUENTIAL`() {
        val edge = Edge(from = "build", to = "test")

        assertEquals(EdgeKind.SEQUENTIAL, edge.kind)
    }

    @Test
    fun `blank from or to is rejected`() {
        assertThrows<IllegalArgumentException> { Edge(from = "", to = "test") }
        assertThrows<IllegalArgumentException> { Edge(from = "build", to = "  ") }
    }

    @Test
    fun `self edges are rejected`() {
        val ex = assertThrows<IllegalArgumentException> { Edge(from = "build", to = "build") }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("self-edge"))
    }

    @Test
    fun `PARALLEL and CONDITIONAL edge kinds are accepted by the M2 compiler`() {
        Edge(from = "build", to = "test", kind = EdgeKind.PARALLEL)
        Edge(from = "build", to = "test", kind = EdgeKind.CONDITIONAL)

        // no exceptions — both are forward declarations on the M2 surface
    }
}

class StageTest {

    @Test
    fun `blank stage name is rejected`() {
        assertThrows<IllegalArgumentException> {
            Stage(name = "", steps = listOf("build"))
        }
    }

    @Test
    fun `empty stage step list is rejected`() {
        assertThrows<IllegalArgumentException> {
            Stage(name = "build-stage", steps = emptyList())
        }
    }

    @Test
    fun `duplicate stage steps are rejected`() {
        assertThrows<IllegalArgumentException> {
            Stage(name = "build-stage", steps = listOf("build", "build"))
        }
    }
}
