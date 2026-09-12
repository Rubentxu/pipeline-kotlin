package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Unit tests for CoreWaitUntilStep registry candidate.
 *
 * G1: CoreWaitUntilStep is registered as a candidate WITHOUT changing LEGACY_PLUGIN_IDS.
 * This test verifies the registry candidate's basic contract without touching legacy paths.
 */
@Timeout(10)
class CoreWaitUntilStepUnitTest {

    // ===== 1. identity =====

    @Test
    fun `identity — CoreWaitUntilStep KEY is core dot waitUntil`() {
        assertEquals(PluginStepId("core.waitUntil"), CoreWaitUntilStep.KEY)
        assertEquals("core.waitUntil", CoreWaitUntilStep.KEY.value)
    }

    @Test
    fun `identity — duplicate registration fails`() {
        val r = InMemoryStepRegistry()
        CoreWaitUntilStep.registerInto(r)
        assertTrue(
            runCatching { CoreWaitUntilStep.registerInto(r) }.isFailure,
            "duplicate registration of core.waitUntil must fail",
        )
    }

    // ===== 2. contract completeness =====

    @Test
    fun `contract — has descriptor, codecs, and EVENT_SINK capability`() {
        val contract = CoreWaitUntilStep.definition.contract
        assertEquals(CoreWaitUntilStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "StepDescriptor must be present")
        assertEquals("waitUntil", contract.descriptor.name)
        assertNotNull(contract.inputCodec, "input codec must be present")
        assertNotNull(contract.outputCodec, "output codec must be present")
        assertTrue(
            contract.requiredCapabilities.contains(EVENT_SINK_CAPABILITY),
            "core.waitUntil MUST declare EVENT_SINK_CAPABILITY",
        )
    }

    // ===== 3. input codec =====

    @Test
    fun `codec input — WaitUntilInput encodes to canonical dsl-v1 envelope`() {
        val input = WaitUntilInput(initialRecurrencePeriod = 500L, quiet = false)
        val encoded = CoreWaitUntilStep.definition.contract.inputCodec.encode(input)
        assertTrue(encoded.value.contains("\"kind\":\"waitUntil\""))
        assertTrue(encoded.value.contains("\"initialRecurrencePeriod\":500"))
        assertTrue(encoded.value.contains("\"quiet\":false"))
    }

    @Test
    fun `codec input — round-trip reconstructs WaitUntilInput`() {
        val original = WaitUntilInput(initialRecurrencePeriod = 2000L, quiet = true)
        val encoded = CoreWaitUntilStep.definition.contract.inputCodec.encode(original)
        val decoded = CoreWaitUntilStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(original.initialRecurrencePeriod, decoded.initialRecurrencePeriod)
        assertEquals(original.quiet, decoded.quiet)
    }

    @Test
    fun `codec input — decode rejects non-waitUntil kind`() {
        val bad = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue("""{"kind":"echo"}""")
        assertTrue(
            runCatching { CoreWaitUntilStep.definition.contract.inputCodec.decode(bad) }.isFailure,
            "input decode must fail closed on non-waitUntil kind",
        )
    }

    // ===== 4. output codec =====

    @Test
    fun `codec output — WaitUntilOutput round-trips`() {
        val original = WaitUntilOutput(resultOutcome = "completed", totalAttempts = 3, totalDurationMs = 1500L)
        val encoded = CoreWaitUntilStep.definition.contract.outputCodec.encode(original)
        val decoded = CoreWaitUntilStep.definition.contract.outputCodec.decode(encoded)
        assertEquals(original.resultOutcome, decoded.resultOutcome)
        assertEquals(original.totalAttempts, decoded.totalAttempts)
        assertEquals(original.totalDurationMs, decoded.totalDurationMs)
    }

    // ===== 5. production registry =====

    @Test
    fun `registry — production factory contains core dot waitUntil`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(
            registry.contains(CoreWaitUntilStep.KEY),
            "production registry must contain core.waitUntil",
        )
    }

    // ===== 6. structural family =====

    @Test
    fun `structural family — core waitUntil stays LegacyCore while in LEGACY_PLUGIN_IDS (no authority flip at G1)`() {
        // While "core.waitUntil" remains in LEGACY_PLUGIN_IDS, StructuralFamilyResolver returns
        // LegacyCore for this key. This test pins that property.
        assertTrue(
            "core.waitUntil" in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.waitUntil must remain in LEGACY_PLUGIN_IDS at G1",
        )
    }
}
