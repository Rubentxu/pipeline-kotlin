package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecRegistryTest {

    private fun spec() = PipelineSpec(
        stages = listOf(
            StageSpec(name = "s", steps = listOf(StepSpec.Echo(text = "hi"))),
        )
    )

    @Test
    fun `register then resolve returns the same spec instance`() {
        val registry = SpecRegistry()
        val id = DefinitionId("d1")
        val s = spec()

        registry.register(id, s)

        assertTrue(registry.resolve(id) === s)
    }

    @Test
    fun `resolve with unknown id fails closed with an actionable message`() {
        val registry = SpecRegistry()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            registry.resolve(DefinitionId("never-registered"))
        }

        assertTrue(ex.message!!.contains("No PipelineSpec registered"))
    }

    @Test
    fun `re-registering the same id replaces the previous spec`() {
        val registry = SpecRegistry()
        val id = DefinitionId("d1")
        val first = spec()
        val second = spec()

        registry.register(id, first)
        registry.register(id, second)

        assertTrue(registry.resolve(id) === second)
        assertEquals(1, registry.size())
    }

    @Test
    fun `size tracks the number of distinct registered ids`() {
        val registry = SpecRegistry()

        assertEquals(0, registry.size())
        registry.register(DefinitionId("a"), spec())
        registry.register(DefinitionId("b"), spec())
        assertEquals(2, registry.size())
    }
}
