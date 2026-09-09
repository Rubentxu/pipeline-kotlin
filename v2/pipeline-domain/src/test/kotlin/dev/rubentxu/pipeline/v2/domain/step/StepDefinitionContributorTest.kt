package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * LB-02 / EP-F1 — generic Step contribution SPI + registry composition.
 *
 * Discovery is generic and fail-closed: zero contributors leaves the core registry unchanged; a
 * duplicate StepKey (core+plugin or plugin A+plugin B) is rejected with a diagnostic naming BOTH the
 * StepKey and the contributor id. No concrete plugin class is known here.
 */
class StepDefinitionContributorTest {

    private fun stringDefinition(key: String): StepDefinition<String, String> {
        val codec = object : StepCodec<String> {
            override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
            override fun decode(encoded: EncodedStepValue): String = encoded.value
        }
        return object : StepDefinition<String, String> {
            override val contract: StepContract<String, String> = StepContract(
                key = dev.rubentxu.pipeline.v2.domain.PluginStepId(key),
                descriptor = StepDescriptor(stepId = key, name = key, configRef = "", executionLocation = ExecutionLocation.CONTROLLER),
                inputCodec = codec,
                outputCodec = codec,
            )
            override val handler: StepHandler<String, String> = StepHandler { input, _ -> input }
        }
    }

    private fun contributor(id: String, vararg defs: StepDefinition<*, *>) = object : StepDefinitionContributor {
        override val id: String = id
        override fun definitions(): Iterable<StepDefinition<*, *>> = defs.toList()
    }

    @Test
    fun `zero contributors leaves registry with only pre-seeded core`() {
        val registry = InMemoryStepRegistry()
        registry.register(stringDefinition("core.x"))
        registry.registerContributors(emptyList())
        assertTrue(registry.contains(dev.rubentxu.pipeline.v2.domain.PluginStepId("core.x")))
    }

    @Test
    fun `contributor with a distinct key registers and is resolvable`() {
        val registry = InMemoryStepRegistry()
        val contributor = contributor("example.uppercase", stringDefinition("example.uppercase"))
        registry.registerContributors(listOf(contributor))
        assertTrue(registry.contains(dev.rubentxu.pipeline.v2.domain.PluginStepId("example.uppercase")))
    }

    @Test
    fun `duplicate key across core and plugin fails closed naming contributor`() {
        val registry = InMemoryStepRegistry()
        registry.register(stringDefinition("example.uppercase")) // simulate core occupying the key
        val plugin = contributor("example.uppercase-plugin", stringDefinition("example.uppercase"))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            registry.registerContributors(listOf(plugin))
        }
        assertTrue(ex.message!!.contains("example.uppercase"), "diagnostic must name the StepKey")
        assertTrue(ex.message!!.contains("example.uppercase-plugin"), "diagnostic must name the contributor")
    }

    @Test
    fun `duplicate key across two plugins fails closed (no first-wins)`() {
        val registry = InMemoryStepRegistry()
        val a = contributor("plugin-a", stringDefinition("example.shared"))
        registry.registerContributors(listOf(a))
        val b = contributor("plugin-b", stringDefinition("example.shared"))
        assertThrows(IllegalArgumentException::class.java) {
            registry.registerContributors(listOf(b))
        }
    }

    @Test
    fun `composite keys set reflects exactly the registered definitions`() {
        val registry = InMemoryStepRegistry()
        registry.register(stringDefinition("core.echo"))
        registry.registerContributors(listOf(contributor("p", stringDefinition("example.a"))))
        val keys = registry.keys().map { it.value }.toSet()
        assertEquals(setOf("core.echo", "example.a"), keys)
    }
}
