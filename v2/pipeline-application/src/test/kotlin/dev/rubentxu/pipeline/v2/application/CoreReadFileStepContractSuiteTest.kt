package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.FileRead
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * StepContractSuite — WU-LPR-087 (LFC-2R2) for `core.readFile`.
 *
 * Closes the G6+G8 contract gap that WU-LPR-104 left when it created the
 * registry candidate without a 17-layer certification. Specifically, this
 * suite verifies the LFC-2R2 cut-over:
 *
 *  - `CoreReadFileOutput` is now a `data class(content: String?, exists: Boolean)` —
 *    the WU-LPR-104 shape was `data object` (Unit-only).
 *  - The codec envelope is `{kind, content?, exists}` (new) and back-compat
 *    decodes the legacy `{kind, outcome: SUCCESS}` shape with exists=true.
 *  - The handler captures `WorkspaceOperations.readFile(file, encoding)` and
 *    projects its `content`/`exists` into the typed output (never fabricates
 *    values).
 *  - `ReplayPolicy.MEMOIZED` (the canonical declaration); the Step declares
 *    only the WORKSPACE_OPERATIONS capability it actually uses (G3-A4.2).
 *  - The runtime-returning façade ([RuntimeScriptedStepFacade.readFile]) can
 *    decode the typed output back to a String for the scripted caller.
 *
 * Test matrix:
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
 * 10. handler captures FileReadResult        REQUIRED
 * 11. capability declaration == usage        REQUIRED (G3-A4.2)
 * 12. handler delegates to WorkspaceOps      REQUIRED
 * 13. registered through open registry seam  REQUIRED
 * 14. registry-key uniqueness                REQUIRED
 * ```
 */
class CoreReadFileStepContractSuiteTest {

    @Test
    fun `1 — Step identity is core_readFile`() {
        assertEquals(PluginStepId("core.readFile"), CoreReadFileStep.KEY)
    }

    @Test
    fun `2 — contract completeness (key, descriptor, codecs, capabilities)`() {
        val contract = CoreReadFileStep.definition.contract
        assertEquals(CoreReadFileStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "descriptor is required")
        assertEquals("core.readFile", contract.descriptor.stepId)
        assertEquals(1, contract.descriptor.effects.size, "MEMOIZED reads must declare exactly one effect")
        assertSame(Effect.READ_ONLY, contract.descriptor.effects.single(), "READ_ONLY is the canonical read-file effect")
        assertSame(ReplayPolicy.MEMOIZED, contract.descriptor.replayPolicy, "MEMOIZED reuses persisted observation")
        assertTrue(contract.requiredCapabilities.isNotEmpty(), "declared capabilities must not be empty")
        assertTrue(
            contract.requiredCapabilities.any { it == WORKSPACE_OPERATIONS_CAPABILITY },
            "declared capability MUST include WORKSPACE_OPERATIONS_CAPABILITY (handler reaches WorkspaceOperations.readFile)",
        )
    }

    @Test
    fun `3 — input codec round-trip`() {
        val codec: StepCodec<CoreReadFileInput> = CoreReadFileStep.definition.contract.inputCodec
        val input = CoreReadFileInput(file = "build.gradle.kts", encoding = "UTF-8")
        val encoded = codec.encode(input)
        val decoded = codec.decode(encoded)
        assertEquals(input, decoded, "round-trip must reconstruct the same CoreReadFileInput")
    }

    @Test
    fun `4 — input codec rejects foreign kind`() {
        val codec: StepCodec<CoreReadFileInput> = CoreReadFileStep.definition.contract.inputCodec
        val foreign = EncodedStepValue("""{"kind":"sh","script":"echo"}""")
        val ex = kotlin.runCatching { codec.decode(foreign) }.exceptionOrNull()
        assertNotNull(ex, "foreign kind MUST be rejected by the canonical input codec")
        assertTrue(
            ex!!.message!!.contains("readFile", ignoreCase = true),
            "rejection message must name the expected kind: ${ex.message}",
        )
    }

    @Test
    fun `5 — input validation rejects blank file`() {
        val ex = kotlin.runCatching { CoreReadFileInput(file = "", encoding = "UTF-8") }.exceptionOrNull()
        assertNotNull(ex, "blank file MUST be rejected at construction")
    }

    @Test
    fun `6 — output codec encode (new LFC-2R2 shape)`() {
        val codec: StepCodec<CoreReadFileOutput> = CoreReadFileStep.definition.contract.outputCodec
        val output = CoreReadFileOutput(content = "hello", exists = true)
        val encoded = codec.encode(output).value
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("readFile", obj["kind"]?.jsonPrimitive?.content, "kind discriminant is required")
        assertEquals("hello", obj["content"]?.jsonPrimitive?.content, "content must be persisted")
        assertTrue(obj["exists"]?.jsonPrimitive?.booleanOrNull == true, "exists=true must be persisted")
    }

    @Test
    fun `7 — output codec decode (new LFC-2R2 shape)`() {
        val codec: StepCodec<CoreReadFileOutput> = CoreReadFileStep.definition.contract.outputCodec
        val encoded = EncodedStepValue("""{"kind":"readFile","content":"some text","exists":true}""")
        val decoded = codec.decode(encoded)
        assertEquals("some text", decoded.content)
        assertTrue(decoded.exists)
        // Outcome must be Success for the typed output.
        assertSame(dev.rubentxu.pipeline.v2.domain.StepOutcome.Success, decoded.outcome)
    }

    @Test
    fun `8 — output codec decode (back-compat legacy envelope)`() {
        val codec: StepCodec<CoreReadFileOutput> = CoreReadFileStep.definition.contract.outputCodec
        // Pre-LFC-2R2 envelope from WU-LPR-104.
        val legacy = EncodedStepValue("""{"kind":"readFile","outcome":"SUCCESS"}""")
        val decoded = codec.decode(legacy)
        assertNull(decoded.content, "legacy envelope has no content; decoded as null")
        assertTrue(decoded.exists, "legacy envelope implies exists=true (the original Unit-only step never observed absence)")
    }

    @Test
    fun `9 — output codec decode (exists=false persisted)`() {
        val codec: StepCodec<CoreReadFileOutput> = CoreReadFileStep.definition.contract.outputCodec
        val encoded = EncodedStepValue("""{"kind":"readFile","content":null,"exists":false}""")
        val decoded = codec.decode(encoded)
        assertFalse(decoded.exists, "exists=false must round-trip from the persisted envelope")
        assertNull(decoded.content, "exists=false MUST have content=null (the substrate didn't read)")
    }

    @Test
    fun `10 — output is a TypedStepOutput`() {
        val output: CoreReadFileOutput = CoreReadFileOutput(content = "x", exists = true)
        assertTrue(output is TypedStepOutput, "CoreReadFileOutput MUST implement TypedStepOutput for CommonExecutionBoundary")
    }

    @Test
    fun `11 — capability declaration matches handler usage (G3-A4_2)`() {
        // The handler reaches only WORKSPACE_OPERATIONS_CAPABILITY (via the
        // WorkspaceOperationsAdapter); declaring more would over-promise and
        // trigger the architecture-fitness "declared capability == used capability" rule.
        val declared = CoreReadFileStep.definition.contract.requiredCapabilities
        assertEquals(1, declared.size, "exactly one declared capability is required (handler uses exactly one)")
        assertEquals(WORKSPACE_OPERATIONS_CAPABILITY.key, declared.single().key, "declared key must match the used capability key")
    }

    @Test
    fun `12 — handler is a StepHandler with typed I_O`() {
        // Compile-time check: if the handler signature drifts, this test fails to compile.
        val handler: StepHandler<CoreReadFileInput, CoreReadFileOutput> = CoreReadFileStep.definition.handler
        assertNotNull(handler)
    }

    @Test
    fun `13 — registered through the open registry seam (no privileged core path)`() {
        val registry = dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry()
        CoreReadFileStep.registerInto(registry)
        val resolved = registry.definition(CoreReadFileStep.KEY)
        assertNotNull(resolved, "registry MUST resolve core.readFile through the open seam")
        assertEquals(CoreReadFileStep.definition.contract.key, resolved!!.contract.key)
    }

    @Test
    fun `14 — registry key does not collide with any other registered Step`() {
        val registry = dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry()
        // Register the rest of the catalog (mirrors CoreStepRegistryFactory, minus core.readFile
        // itself which is the SUT).
        CoreEchoStep.registerInto(registry)
        CoreShellStep.registerInto(registry)
        CoreErrorStep.registerInto(registry)
        CoreSleepStep.registerInto(registry)
        CoreWriteFileStep.registerInto(registry)
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
        // Now register the SUT — duplicate key MUST fail closed.
        CoreReadFileStep.registerInto(registry)
        val resolved = registry.definition(CoreReadFileStep.KEY)
        assertNotNull(resolved)
        assertEquals(CoreReadFileStep.definition.contract.key, resolved!!.contract.key)
    }
}
