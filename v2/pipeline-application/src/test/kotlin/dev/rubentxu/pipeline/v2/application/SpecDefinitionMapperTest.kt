package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.EdgeKind
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDefinitionMapperTest {

    private fun multiStageSpec() = PipelineSpec(
        stages = listOf(
            StageSpec(
                name = "build",
                steps = listOf(StepSpec.Echo(text = "a"), StepSpec.Echo(text = "b")),
            ),
            StageSpec(
                name = "test",
                steps = listOf(StepSpec.Echo(text = "c")),
            ),
        )
    )

    @Test
    fun `steps are flattened with synthetic ids matching walker control-dir naming`() {
        val definition = SpecDefinitionMapper.toDefinition(multiStageSpec(), DefinitionId("d1"))

        assertEquals(
            listOf("s0-0", "s0-1", "s1-0"),
            definition.steps.map { it.id },
        )
    }

    @Test
    fun `edges form a single linear chain reproducing the legacy semantic order`() {
        val definition = SpecDefinitionMapper.toDefinition(multiStageSpec(), DefinitionId("d1"))

        assertEquals(
            listOf("s0-0" to "s0-1", "s0-1" to "s1-0"),
            definition.edges.map { it.from to it.to },
        )
        definition.edges.forEach { edge ->
            assertEquals(EdgeKind.SEQUENTIAL, edge.kind)
        }
    }

    @Test
    fun `definition id is preserved exactly`() {
        val id = DefinitionId("abc123")

        assertEquals(id, SpecDefinitionMapper.toDefinition(multiStageSpec(), id).id)
    }

    @Test
    fun `mapping is deterministic — same spec always produces the same definition`() {
        val first = SpecDefinitionMapper.toDefinition(multiStageSpec(), DefinitionId("d1"))
        val second = SpecDefinitionMapper.toDefinition(multiStageSpec(), DefinitionId("d1"))

        assertEquals(first, second)
    }

    @Test
    fun `empty spec produces a definition with no steps and no edges`() {
        val empty = PipelineSpec(stages = emptyList())

        val definition = SpecDefinitionMapper.toDefinition(empty, DefinitionId("d1"))

        assertEquals(0, definition.steps.size)
        assertEquals(0, definition.edges.size)
    }

    @Test
    fun `step descriptors carry type and configRef for the transition period`() {
        val definition = SpecDefinitionMapper.toDefinition(multiStageSpec(), DefinitionId("d1"))

        definition.steps.forEach { step ->
            assertTrue(step.type.isNotBlank())
            assertEquals(step.id, step.configRef, "configRef defaults to the synthetic id")
        }
    }
}
