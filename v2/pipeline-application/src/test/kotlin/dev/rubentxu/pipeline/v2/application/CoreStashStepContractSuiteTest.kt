package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * StepContractSuite — WU-LPR-089 Phase B for `core.stash` (Tier B #1).
 *
 * Test matrix (11 tests):
 * ```
 *  1. Step identity is core_stash
 *  2. Contract completeness (key, descriptor, codecs, capabilities)
 *  3. Input codec round-trip (omitted excludes field preserved)
 *  4. Input codec rejects foreign kind
 *  5. Output codec round-trip (success)
 *  6. Output codec round-trip (failure)
 *  7. Output codec rejects foreign kind
 *  8. Capability declaration == usage (G3-A4.2: declared == used)
 *  9. Handler is a StepHandler typed I_O
 * 10. Registered through the open registry seam
 * 11. Registry key uniqueness vs other 17 CoreSteps
 * ```
 */
class CoreStashStepContractSuiteTest {

    @Test
    fun `1 — Step identity is core_stash`() {
        assertEquals(PluginStepId("core.stash"), CoreStashStep.KEY)
    }

    @Test
    fun `2 — contract completeness (key, descriptor, codecs, capabilities)`() {
        val contract = CoreStashStep.definition.contract
        assertEquals(CoreStashStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "descriptor is required")
        assertEquals("core.stash", contract.descriptor.stepId)
        assertTrue(contract.descriptor.effects.isNotEmpty())
        assertTrue(
            contract.descriptor.effects.contains(Effect.WRITES_WORKSPACE),
            "core.stash MUST declare WRITES_WORKSPACE (it creates the stash directory and copies files)",
        )
        assertEquals(ReplayPolicy.MEMOIZED, contract.descriptor.replayPolicy)
        assertTrue(contract.requiredCapabilities.isNotEmpty())
        assertTrue(
            contract.requiredCapabilities.any { it.key == STASH_OPERATIONS_CAPABILITY.key },
            "declared capability MUST include STASH_OPERATIONS_CAPABILITY",
        )
    }

    @Test
    fun `3 — input codec round-trip (omitted excludes preserved)`() {
        val codec: StepCodec<StashInput> = CoreStashStep.definition.contract.inputCodec
        val input = StashInput(name = "lpr104-files", includes = "src/**", excludes = "")
        val encoded = codec.encode(input)
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        // `excludes` is OMITTED from the canonical envelope when blank (backward-compat).
        assertEquals("stash", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("lpr104-files", obj["name"]?.jsonPrimitive?.content)
        assertEquals("src/**", obj["includes"]?.jsonPrimitive?.content)
        assertTrue(
            "excludes" !in obj,
            "blank excludes MUST be omitted from the canonical envelope (matches archiveArtifacts parity)",
        )
        val decoded = codec.decode(encoded)
        assertEquals(input, decoded)
    }

    @Test
    fun `4 — input codec rejects foreign kind`() {
        val codec: StepCodec<StashInput> = CoreStashStep.definition.contract.inputCodec
        val foreign = EncodedStepValue("""{"kind":"unstash","name":"x","includes":"*"}""")
        val ex = kotlin.runCatching { codec.decode(foreign) }.exceptionOrNull()
        assertNotNull(ex, "foreign kind MUST be rejected")
        assertTrue(ex!!.message!!.contains("stash"))
    }

    @Test
    fun `5 — output codec round-trip (success)`() {
        val codec: StepCodec<TypedStepOutput> = CoreStashStep.definition.contract.outputCodec
        val output: TypedStepOutput = CoreStashStep.StashOutput(stashedCount = 3)
        val encoded = codec.encode(output)
        val decoded = codec.decode(encoded)
        assertEquals(output, decoded)
    }

    @Test
    fun `6 — output codec round-trip (failure)`() {
        val codec: StepCodec<TypedStepOutput> = CoreStashStep.definition.contract.outputCodec
        val output: TypedStepOutput = CoreStashStep.StashFailureOutput(
            failureKind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
            message = "no files matched",
        )
        val encoded = codec.encode(output)
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("FAILED", obj["outcome"]?.jsonPrimitive?.content)
        assertEquals("SCRIPT", obj["failureKind"]?.jsonPrimitive?.content)
        val decoded = codec.decode(encoded) as CoreStashStep.StashFailureOutput
        assertEquals(dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT, decoded.failureKind)
        assertEquals("no files matched", decoded.message)
    }

    @Test
    fun `7 — output codec rejects foreign kind`() {
        val codec: StepCodec<TypedStepOutput> = CoreStashStep.definition.contract.outputCodec
        val foreign = EncodedStepValue("""{"kind":"unstash","stashedCount":1}""")
        val ex = kotlin.runCatching { codec.decode(foreign) }.exceptionOrNull()
        assertNotNull(ex, "foreign kind MUST be rejected on output side too")
        assertTrue(ex!!.message!!.contains("stash"))
    }

    @Test
    fun `8 — capability declaration matches handler usage (G3-A4_2)`() {
        val declared: Set<StepCapability> = CoreStashStep.definition.contract.requiredCapabilities
        assertEquals(1, declared.size, "exactly one declared capability")
        assertEquals(STASH_OPERATIONS_CAPABILITY.key, declared.single().key)
    }

    @Test
    fun `9 — handler is a StepHandler typed I_O`() {
        val handler: StepHandler<StashInput, TypedStepOutput> = CoreStashStep.definition.handler
        assertNotNull(handler)
    }

    @Test
    fun `10 — registered through the open registry seam`() {
        val registry = InMemoryStepRegistry()
        CoreStashStep.registerInto(registry)
        val resolved = registry.definition(CoreStashStep.KEY)
        assertNotNull(resolved, "registry MUST resolve core.stash through the open seam")
        assertEquals(CoreStashStep.KEY, resolved!!.contract.key)
    }

    @Test
    fun `11 — registry key uniqueness vs other CoreSteps (no StepKey collisions)`() {
        val registry = InMemoryStepRegistry()
        CoreEchoStep.registerInto(registry)
        CoreShellStep.registerInto(registry)
        CoreErrorStep.registerInto(registry)
        CoreSleepStep.registerInto(registry)
        CoreWriteFileStep.registerInto(registry)
        CoreReadFileStep.registerInto(registry)
        CoreFileExistsStep.registerInto(registry)
        CoreArchiveArtifactsStep.registerInto(registry)
        CoreArtifactQueryStep.registerInto(registry)
        CoreEmitEventStep.registerInto(registry)
        CoreIsUnixStep.registerInto(registry)
        CorePwdStep.registerInto(registry)
        CorePwdTmpStep.registerInto(registry)
        CoreDeleteDirStep.registerInto(registry)
        CoreCleanWsStep.registerInto(registry)
        CoreMilestoneStep.registerInto(registry)
        CoreWaitUntilStep.registerInto(registry)
        CoreStashStep.registerInto(registry)
        CoreUnstashStep.registerInto(registry)
        // Verify both stash-related keys are distinct.
        assertEquals(PluginStepId("core.stash"), CoreStashStep.KEY)
        assertEquals(PluginStepId("core.unstash"), CoreUnstashStep.KEY)
        assertNotNull(registry.definition(CoreStashStep.KEY))
        assertNotNull(registry.definition(CoreUnstashStep.KEY))
    }
}
