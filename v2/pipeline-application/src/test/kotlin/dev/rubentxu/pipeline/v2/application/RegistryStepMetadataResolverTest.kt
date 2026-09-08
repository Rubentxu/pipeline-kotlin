package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * CDE.3-d5b: freezes the registry/definition [StepMetadataResolver] composite. A registered non-core
 * plugin step resolves its durable metadata from its definition descriptor (effects + replayPolicy); a
 * core key delegates to the legacy core authority unchanged; a key that is neither core nor registered
 * is a hard defect.
 */
@Timeout(10)
class RegistryStepMetadataResolverTest {

    private val pluginKey: PluginStepId = PluginStepId("acme.greet")

    private val stringCodec = object : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private fun pluginDefinition(): StepDefinition<String, String> = object : StepDefinition<String, String> {
        override val contract: StepContract<String, String> = StepContract(
            key = pluginKey,
            descriptor = StepDescriptor(
                stepId = pluginKey.value,
                name = "greet",
                configRef = "",
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.WRITES_WORKSPACE, Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.RERUN,
            ),
            inputCodec = stringCodec,
            outputCodec = stringCodec,
        )
        override val handler: StepHandler<String, String> = StepHandler { input, _ -> input }
    }

    private fun registry(): InMemoryStepRegistry = InMemoryStepRegistry().apply {
        register(pluginDefinition())
    }

    @Test
    fun `a registered non-core plugin step resolves metadata from its definition descriptor`() {
        val resolver = RegistryStepMetadataResolver.composite(registry())
        val metadata = resolver.resolve(pluginKey)
        assertEquals(ReplayPolicy.RERUN, metadata!!.replayPolicy)
        assertEquals(setOf(Effect.WRITES_WORKSPACE, Effect.READ_ONLY), metadata.effects)
    }

    @Test
    fun `a core key delegates to the legacy core authority even when absent from the registry`() {
        // The composite delegates still-legacy core keys to the legacy core catalog, NOT to any registry
        // definition, so core semantics (sh) cannot be shadowed by a definition. B1.2c3: core.echo has
        // migrated out of the legacy set, so it is no longer a "core delegates" key here.
        val resolver = RegistryStepMetadataResolver.composite(registry())
        val metadata = resolver.resolve(PluginStepId("core.sh"))
        assertEquals(CanonicalCoreStepMetadata.metadata("core.sh").replayPolicy, metadata!!.replayPolicy)
        assertEquals(CanonicalCoreStepMetadata.metadata("core.sh").effects, metadata.effects)
    }

    @Test
    fun `a key that is neither core nor registered is a hard defect`() {
        val resolver = RegistryStepMetadataResolver.composite(registry())
        assertThrows(EngineInvariantViolation::class.java) {
            resolver.resolve(PluginStepId("acme.not-registered"))
        }
    }
}
