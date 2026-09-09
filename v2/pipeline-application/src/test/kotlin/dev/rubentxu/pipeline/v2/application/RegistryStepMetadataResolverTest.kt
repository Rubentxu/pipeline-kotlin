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
 * legacy core key delegates to the legacy core authority unchanged; a key that is neither core nor
 * registered is a hard defect.
 *
 * LB-02 / A4: `core.sh` is no longer a legacy key after the REGISTRY_PRIMARY flip. The composite now
 * resolves `core.sh` through the open registry (its descriptor on `CoreShellStep`). The
 * "neither core nor registered" failure is the correct outcome if a test passes a registry without
 * `core.sh`.
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
    fun `a remaining legacy core key delegates to the legacy core authority even when absent from the registry`() {
        // The composite delegates still-legacy core keys to the legacy core catalog, NOT to any registry
        // definition, so core semantics (e.g. core.error) cannot be shadowed by a definition.
        // LB-02 / A4: `core.sh` is no longer in this set; `core.error` is the canonical example.
        val resolver = RegistryStepMetadataResolver.composite(registry())
        val metadata = resolver.resolve(PluginStepId("core.error"))
        assertEquals(CanonicalCoreStepMetadata.metadata("core.error").replayPolicy, metadata!!.replayPolicy)
        assertEquals(CanonicalCoreStepMetadata.metadata("core.error").effects, metadata.effects)
    }

    @Test
    fun `core sh is no longer a legacy key and resolves through the registry when registered`() {
        // LB-02 / A4 (REGISTRY_PRIMARY flip): after removing "core.sh" from LEGACY_PLUGIN_IDS,
        // the composite falls into the registry branch for `core.sh` and reads the durable
        // metadata from `CoreShellStep.descriptor`. The legacy row is physically present but
        // unreachable through the composite for `core.sh`.
        val resolver = RegistryStepMetadataResolver.composite(CoreStepRegistryFactory.registry())
        val metadata = resolver.resolve(CoreShellStep.KEY)
        assertEquals(
            CoreShellStep.definition.contract.descriptor.replayPolicy,
            metadata!!.replayPolicy,
        )
        assertEquals(
            CoreShellStep.definition.contract.descriptor.effects.toSet(),
            metadata.effects,
        )
        assertEquals(
            CoreShellStep.definition.contract.descriptor.recoveryPolicy,
            metadata.recoveryPolicy,
        )
    }

    @Test
    fun `core sh absent from the registry is a hard defect (post-flip)`() {
        // LB-02 / A4: with `core.sh` removed from LEGACY_PLUGIN_IDS, the composite resolver MUST
        // NOT fall back to `CanonicalCoreStepMetadata` for `core.sh`. A registry without `core.sh`
        // is now a hard defect (EngineInvariantViolation), not a silent legacy fallback.
        val resolver = RegistryStepMetadataResolver.composite(registry())
        assertThrows(EngineInvariantViolation::class.java) {
            resolver.resolve(PluginStepId("core.sh"))
        }
    }

    @Test
    fun `a key that is neither core nor registered is a hard defect`() {
        val resolver = RegistryStepMetadataResolver.composite(registry())
        assertThrows(EngineInvariantViolation::class.java) {
            resolver.resolve(PluginStepId("acme.not-registered"))
        }
    }
}
