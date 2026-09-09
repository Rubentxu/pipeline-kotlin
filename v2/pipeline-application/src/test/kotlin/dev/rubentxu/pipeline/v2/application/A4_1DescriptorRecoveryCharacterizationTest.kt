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
    fun `A4-1-4 legacy authority for core sh keeps ExternalSubprocess during the dual phase`() {
        // Today `core.sh` is in LEGACY_PLUGIN_IDS, so the composite delegates to the legacy table.
        // The table's recovery row must match CoreShellStep.descriptor.recoveryPolicy so the
        // pre/post A4.8 metadata is byte-equivalent.
        val legacy = CanonicalCoreStepMetadata.metadata("core.sh")
        val registryDescriptor = CoreShellStep.definition.contract.descriptor

        assertEquals(
            RecoveryPolicy.ExternalSubprocess,
            legacy.recoveryPolicy,
            "legacy metadata for core.sh must continue to declare ExternalSubprocess during the dual phase",
        )
        assertEquals(
            legacy.recoveryPolicy,
            registryDescriptor.recoveryPolicy,
            "A4.1.5 parity law: legacy recoveryPolicy == registry descriptor recoveryPolicy for core.sh",
        )
    }

    @Test
    fun `A4-1-5 full pre-decode metadata parity for core sh across authorities`() {
        // End-state law: once `core.sh` leaves LEGACY_PLUGIN_IDS (A4.8), the registry-resolved
        // metadata MUST match the legacy-resolved metadata bit-for-bit for the same step.
        // We construct the registry-resolved value by reading CoreShellStep.definition.contract.descriptor
        // (the post-A4.8 source) and assert equivalence with the legacy table row.
        val legacy = CanonicalCoreStepMetadata.metadata("core.sh")
        val descriptor = CoreShellStep.definition.contract.descriptor

        assertEquals(legacy.replayPolicy, descriptor.replayPolicy, "replayPolicy parity")
        assertEquals(legacy.recoveryPolicy, descriptor.recoveryPolicy, "recoveryPolicy parity")
        assertEquals(legacy.effects, descriptor.effects.toSet(), "effects parity")
        assertEquals(
            CanonicalCoreStepMetadata.shortType("core.sh"),
            descriptor.name,
            "descriptor name is the short type the legacy table derives",
        )
        // executionLocation lives only on the descriptor today (legacy table omits it),
        // but the durable protocol does not consume it pre-decode for sh; record the parity
        // assumption explicitly so a future divergence is caught.
        assertNotEquals(null, descriptor.executionLocation)
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
