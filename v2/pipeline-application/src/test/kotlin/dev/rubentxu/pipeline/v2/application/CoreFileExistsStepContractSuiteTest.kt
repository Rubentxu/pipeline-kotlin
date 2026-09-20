package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.FileExistsChecked
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * StepContractSuite — WU-LPR-087 (LFC-2R2) for `core.fileExists`.
 *
 * Closes the G6+G8 contract gap that WU-LPR-104 left when it created the
 * registry candidate without a 17-layer certification. Specifically, this
 * suite verifies the LFC-2R2 cut-over:
 *
 *  - `CoreFileExistsOutput` is now `data class(exists: Boolean)` — the
 *    WU-LPR-104 shape was `data object` (Unit-only). The runtime-returning
 *    façade decodes the `exists` field back to `Boolean` for the scripted
 *    caller.
 *  - The codec envelope is `{kind, exists}` (new) and back-compat decodes
 *    the legacy `{kind, outcome: SUCCESS}` shape with exists=true.
 *  - The handler captures `WorkspaceOperations.fileExists(file)` and
 *    projects its `exists` boolean into the typed output (never fabricates
 *    a value).
 *  - `ReplayPolicy.MEMOIZED`; declared capability == used capability
 *    (G3-A4.2: only `WORKSPACE_OPERATIONS_CAPABILITY`).
 *  - The runtime-returning façade ([RuntimeScriptedStepFacade.fileExists])
 *    can decode the typed output back to a Boolean for the scripted caller.
 *
 * Test matrix (mirrors [CoreReadFileStepContractSuiteTest]):
 * ```
 *  1. identity + descriptor                  REQUIRED
 *  2. contract completeness                  REQUIRED
 *  3. input codec round-trip                 REQUIRED
 *  4. input codec rejection (foreign kind)   REQUIRED
 *  5. input validation (blank file)          REQUIRED
 *  6. output codec encode (new shape)        REQUIRED
 *  7. output codec decode (new shape)        REQUIRED
 *  8. output codec decode (back-compat)      REQUIRED
 *  9. output codec decode (exists=false)     REQUIRED
 * 10. output is a TypedStepOutput            REQUIRED
 * 11. capability declaration == usage        REQUIRED (G3-A4.2)
 * 12. handler is a StepHandler typed I_O     REQUIRED
 * 13. registered through open registry seam  REQUIRED
 * 14. registry-key uniqueness                REQUIRED
 * ```
 */
class CoreFileExistsStepContractSuiteTest {

    @Test
    fun `1 — Step identity is core_fileExists`() {
        assertEquals(PluginStepId("core.fileExists"), CoreFileExistsStep.KEY)
    }

    @Test
    fun `2 — contract completeness (key, descriptor, codecs, capabilities)`() {
        val contract = CoreFileExistsStep.definition.contract
        assertEquals(CoreFileExistsStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "descriptor is required")
        assertEquals("core.fileExists", contract.descriptor.stepId)
        assertEquals(1, contract.descriptor.effects.size)
        assertSame(Effect.READ_ONLY, contract.descriptor.effects.single())
        assertSame(ReplayPolicy.MEMOIZED, contract.descriptor.replayPolicy)
        assertTrue(contract.requiredCapabilities.isNotEmpty())
        assertTrue(
            contract.requiredCapabilities.any { it == WORKSPACE_OPERATIONS_CAPABILITY },
            "declared capability MUST include WORKSPACE_OPERATIONS_CAPABILITY (handler reaches WorkspaceOperations.fileExists)",
        )
    }

    @Test
    fun `3 — input codec round-trip`() {
        val codec: StepCodec<CoreFileExistsInput> = CoreFileExistsStep.definition.contract.inputCodec
        val input = CoreFileExistsInput(file = "build.gradle.kts")
        val encoded = codec.encode(input)
        val decoded = codec.decode(encoded)
        assertEquals(input, decoded)
    }

    @Test
    fun `4 — input codec rejects foreign kind`() {
        val codec: StepCodec<CoreFileExistsInput> = CoreFileExistsStep.definition.contract.inputCodec
        val foreign = EncodedStepValue("""{"kind":"sh","script":"ls"}""")
        val ex = kotlin.runCatching { codec.decode(foreign) }.exceptionOrNull()
        assertNotNull(ex, "foreign kind MUST be rejected by the canonical input codec")
        assertTrue(
            ex!!.message!!.contains("fileExists", ignoreCase = true),
            "rejection message must name the expected kind: ${ex.message}",
        )
    }

    @Test
    fun `5 — input validation rejects blank file`() {
        val ex = kotlin.runCatching { CoreFileExistsInput(file = "") }.exceptionOrNull()
        assertNotNull(ex, "blank file MUST be rejected at construction")
    }

    @Test
    fun `6 — output codec encode (new LFC-2R2 shape)`() {
        val codec: StepCodec<CoreFileExistsOutput> = CoreFileExistsStep.definition.contract.outputCodec
        val output = CoreFileExistsOutput(exists = true)
        val encoded = codec.encode(output).value
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("fileExists", obj["kind"]?.jsonPrimitive?.content)
        assertTrue(obj["exists"]?.jsonPrimitive?.booleanOrNull == true)
    }

    @Test
    fun `7 — output codec decode (new LFC-2R2 shape)`() {
        val codec: StepCodec<CoreFileExistsOutput> = CoreFileExistsStep.definition.contract.outputCodec
        val encoded = EncodedStepValue("""{"kind":"fileExists","exists":true}""")
        val decoded = codec.decode(encoded)
        assertTrue(decoded.exists)
        assertSame(dev.rubentxu.pipeline.v2.domain.StepOutcome.Success, decoded.outcome)
    }

    @Test
    fun `8 — output codec decode (back-compat legacy envelope)`() {
        val codec: StepCodec<CoreFileExistsOutput> = CoreFileExistsStep.definition.contract.outputCodec
        val legacy = EncodedStepValue("""{"kind":"fileExists","outcome":"SUCCESS"}""")
        val decoded = codec.decode(legacy)
        assertTrue(decoded.exists, "legacy envelope implies exists=true (the original Unit-only step never observed absence)")
    }

    @Test
    fun `9 — output codec decode (exists=false persisted)`() {
        val codec: StepCodec<CoreFileExistsOutput> = CoreFileExistsStep.definition.contract.outputCodec
        val encoded = EncodedStepValue("""{"kind":"fileExists","exists":false}""")
        val decoded = codec.decode(encoded)
        assertFalse(decoded.exists, "exists=false MUST round-trip from the persisted envelope (the predicate observed absence)")
    }

    @Test
    fun `10 — output is a TypedStepOutput`() {
        val output: CoreFileExistsOutput = CoreFileExistsOutput(exists = false)
        assertTrue(output is TypedStepOutput, "CoreFileExistsOutput MUST implement TypedStepOutput for CommonExecutionBoundary")
    }

    @Test
    fun `11 — capability declaration matches handler usage (G3-A4_2)`() {
        val declared = CoreFileExistsStep.definition.contract.requiredCapabilities
        assertEquals(1, declared.size, "exactly one declared capability is required (handler uses exactly one)")
        assertEquals(WORKSPACE_OPERATIONS_CAPABILITY.key, declared.single().key, "declared key must match the used capability key")
    }

    @Test
    fun `12 — handler is a StepHandler with typed I_O`() {
        val handler: StepHandler<CoreFileExistsInput, CoreFileExistsOutput> = CoreFileExistsStep.definition.handler
        assertNotNull(handler)
    }

    @Test
    fun `13 — registered through the open registry seam (no privileged core path)`() {
        val registry = dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry()
        CoreFileExistsStep.registerInto(registry)
        val resolved = registry.definition(CoreFileExistsStep.KEY)
        assertNotNull(resolved, "registry MUST resolve core.fileExists through the open seam")
        assertEquals(CoreFileExistsStep.definition.contract.key, resolved!!.contract.key)
    }

    @Test
    fun `14 — registry key does not collide with any other registered Step`() {
        val registry = dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry()
        // Register the rest of the catalog (mirrors CoreStepRegistryFactory, minus core.fileExists).
        CoreEchoStep.registerInto(registry)
        CoreShellStep.registerInto(registry)
        CoreErrorStep.registerInto(registry)
        CoreSleepStep.registerInto(registry)
        CoreWriteFileStep.registerInto(registry)
        CoreReadFileStep.registerInto(registry)
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
        CoreFileExistsStep.registerInto(registry)
        val resolved = registry.definition(CoreFileExistsStep.KEY)
        assertNotNull(resolved)
        assertEquals(CoreFileExistsStep.definition.contract.key, resolved!!.contract.key)
    }
}
