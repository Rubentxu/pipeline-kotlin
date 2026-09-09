package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * LB-02 / G3-A4.1: descriptor carries the typed recovery property.
 *
 * A4.1.1 — `StepDescriptor.recoveryPolicy` exists with default `RecoveryPolicy.None`.
 * A4.1.2 — `RegistryStepMetadataResolver.composite` propagates `descriptor.recoveryPolicy`
 *          into the resolved `StepMetadata` for non-legacy keys.
 * A4.1.3 — `CoreShellStep.descriptor.recoveryPolicy == RecoveryPolicy.ExternalSubprocess`.
 * A4.1.4 — Legacy metadata remains the compatibility authority for `core.sh` while
 *          `core.sh` is still in `LEGACY_PLUGIN_IDS`.
 * A4.1.5 — Once `core.sh` migrates to registry (post A4.8), the legacy-resolved metadata
 *          and the registry-resolved metadata MUST be byte-equal for the same step.
 *
 * The A4.1.5 byte-equality law is captured here BEFORE the routing flip (A4.8) by
 * temporarily registering `core.sh` and asking the composite to resolve it. Today the
 * composite delegates `core.sh` to the legacy authority, so the equality is observed
 * through the legacy path. After A4.8 the registry path will be exercised; the
 * characterization asserts the END STATE (post-flip) by reading `CoreShellStep.descriptor`
 * directly and asserting the legacy row matches.
 */
@Timeout(10)
class A4_1DescriptorRecoveryCharacterizationTest {

    private val stringCodec = object : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private fun customDefinition(
        key: PluginStepId,
        recovery: RecoveryPolicy,
        replay: ReplayPolicy = ReplayPolicy.MEMOIZED,
        effects: List<Effect> = emptyList(),
        executionLocation: ExecutionLocation = ExecutionLocation.CONTROLLER,
    ): StepDefinition<String, String> = object : StepDefinition<String, String> {
        override val contract: StepContract<String, String> = StepContract(
            key = key,
            descriptor = StepDescriptor(
                stepId = key.value,
                name = key.value,
                configRef = "",
                executionLocation = executionLocation,
                effects = effects,
                replayPolicy = replay,
                recoveryPolicy = recovery,
            ),
            inputCodec = stringCodec,
            outputCodec = stringCodec,
        )
        override val handler: StepHandler<String, String> = StepHandler { input, _ -> input }
    }

    @Test
    fun `A4-1-1 descriptor — recoveryPolicy default is None (preserves Echo unchanged)`() {
        // Default-construct a descriptor with no recoveryPolicy argument: it MUST equal None.
        val d = StepDescriptor(stepId = "x", name = "x", configRef = "")
        assertEquals(RecoveryPolicy.None, d.recoveryPolicy)
    }

    @Test
    fun `A4-1-1 core-echo descriptor — recoveryPolicy is None (backward-compatible default)`() {
        // Echo's contract is built without specifying recoveryPolicy; it stays None.
        // (No need to rebuild CoreEchoStep: the default is None, and the existing definition
        //  was constructed without the new arg, so it inherits None.)
        assertEquals(
            RecoveryPolicy.None,
            CoreEchoStep.definition.contract.descriptor.recoveryPolicy,
            "core.echo recoveryPolicy must remain None for backward compatibility",
        )
    }

    @Test
    fun `A4-1-3 CoreShellStep descriptor — recoveryPolicy equals ExternalSubprocess (declarative recovery)`() {
        assertEquals(
            RecoveryPolicy.ExternalSubprocess,
            CoreShellStep.definition.contract.descriptor.recoveryPolicy,
            "CoreShellStep must declare ExternalSubprocess via its descriptor",
        )
    }

    @Test
    fun `A4-1-2 registry resolver propagates descriptor recoveryPolicy for non-core keys`() {
        val registry = InMemoryStepRegistry().apply {
            register(customDefinition(PluginStepId("acme.recoverable"), RecoveryPolicy.ExternalSubprocess))
            register(customDefinition(PluginStepId("acme.normal"), RecoveryPolicy.None))
        }
        val resolver = RegistryStepMetadataResolver.composite(registry)

        val recoverable = resolver.resolve(PluginStepId("acme.recoverable"))
        assertEquals(RecoveryPolicy.ExternalSubprocess, recoverable!!.recoveryPolicy)

        val normal = resolver.resolve(PluginStepId("acme.normal"))
        assertEquals(RecoveryPolicy.None, normal!!.recoveryPolicy)
    }

    @Test
    fun `A4-1-6 post-S6 registry metadata is the single authority for core sh recovery`() {
        // The dual phase ended at S6: the legacy authority no longer owns core.sh metadata.
        // Recovery metadata for core.sh MUST come exclusively from CoreShellStep.descriptor
        // through the registry resolver.
        assertEquals(
            RecoveryPolicy.ExternalSubprocess,
            CoreShellStep.definition.contract.descriptor.recoveryPolicy,
        )
        // The composite (registry + legacy fallback) resolves core.sh via the descriptor and
        // never a legacy row, because core.sh is absent from the legacy authority.
        val resolver = RegistryStepMetadataResolver.composite(CoreStepRegistryFactory.registry())
        val metadata = resolver.resolve(CoreShellStep.KEY)
        assertEquals(RecoveryPolicy.ExternalSubprocess, metadata!!.recoveryPolicy)
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalCoreStepMetadata.metadata("core.sh")
        }
        // executionLocation lives on the descriptor; the durable protocol does not consume it
        // pre-decode for sh, but it must be set.
        assertNotEquals(null, CoreShellStep.definition.contract.descriptor.executionLocation)
    }

    @Test
    fun `A4-1-2 registry resolver does NOT need per-Step branches — adding a recoverable external plugin requires only a descriptor`() {
        // The point of A4.1.2 is that this composite contains ZERO knowledge of `core.sh`
        // or any concrete plugin id: it reads ONLY `definition.contract.descriptor`. So a
        // future external plugin declaring `recoveryPolicy = ExternalSubprocess` will resolve
        // identically without modifying the resolver. The structural proof: a generic plugin
        // key resolves with the right RecoveryPolicy without any code change in this file.
        val registry = InMemoryStepRegistry().apply {
            register(customDefinition(PluginStepId("ext.external-subprocess"), RecoveryPolicy.ExternalSubprocess))
        }
        val resolver = RegistryStepMetadataResolver.composite(registry)
        val metadata = resolver.resolve(PluginStepId("ext.external-subprocess"))
        assertEquals(RecoveryPolicy.ExternalSubprocess, metadata!!.recoveryPolicy)

        // And no concrete-key branching: assertSame the singleton resolver behaviour by
        // constructing a separate composite and re-resolving — they are independent
        // fun-interface instances but observe the same descriptor-driven semantics.
        val resolver2 = RegistryStepMetadataResolver.composite(registry)
        assertNotEquals(resolver, resolver2)
        assertEquals(
            resolver.resolve(PluginStepId("ext.external-subprocess"))!!.recoveryPolicy,
            resolver2.resolve(PluginStepId("ext.external-subprocess"))!!.recoveryPolicy,
        )
        assertSame(
            RecoveryPolicy.ExternalSubprocess,
            metadata.recoveryPolicy,
        )
    }
}
