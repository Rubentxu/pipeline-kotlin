package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * StepContractSuite — WU-LPR-088 for `core.pwd.tmp` (Tier A #3).
 *
 * Closes the G6 contract gap that existed for `core.pwd.tmp` (the existing
 * `CorePwdTmpStepUnitTest` covers the handler but not the formal 16-layer
 * Strict Validation Set contract matrix required for CERTIFIED).
 *
 * Key contract properties:
 *  - `KEY = core.pwd.tmp` (distinct from `core.pwd` = `core.pwd`).
 *  - Input is `data object PwdTmpInput`; the durable envelope is `{}` (empty
 *    JSON object). Decode is total on `{}` and fails-closed on any other shape.
 *  - Output is `PwdTmpOutput = PwdOutput(path: String)`. The codec envelope is
 *    `{kind: "pwd.tmp", path: <String>}` and the canonical discriminator is
 *    "pwd.tmp" (NOT "pwd" — the kind names the Step family).
 *  - `Effect.WRITES_WORKSPACE` is declared because the tmp directory is
 *    physically created. `ReplayPolicy.MEMOIZED` is declared too: fresh/rerun
 *    creates idempotently, resume/reuse reproduces the persisted observation
 *    without re-creating (per D4 frozen at G2 for the family).
 *  - Required capability: only `TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY`
 *    (G3-A4.2: declared == used).
 *
 * Test matrix:
 * ```
 *  1. identity + descriptor                  REQUIRED
 *  2. contract completeness                  REQUIRED
 *  3. input codec round-trip (empty envelope) REQUIRED
 *  4. output codec round-trip                REQUIRED
 *  5. output codec rejects foreign kind      REQUIRED
 *  6. capability declaration == usage        REQUIRED (G3-A4.2)
 *  7. handler is a StepHandler typed I_O     REQUIRED
 *  8. registered through open registry seam  REQUIRED
 *  9. registry key uniqueness                REQUIRED
 * 10. effect class reflects write            REQUIRED
 * 11. replay policy = MEMOIZED               REQUIRED
 * ```
 */
class CorePwdTmpStepContractSuiteTest {

    @Test
    fun `1 — Step identity is core_pwd_tmp`() {
        assertEquals(PluginStepId("core.pwd.tmp"), CorePwdTmpStep.KEY)
    }

    @Test
    fun `2 — contract completeness (key, descriptor, codecs, capabilities)`() {
        val contract = CorePwdTmpStep.definition.contract
        assertEquals(CorePwdTmpStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "descriptor is required")
        assertEquals("core.pwd.tmp", contract.descriptor.stepId)
        assertTrue(contract.descriptor.effects.isNotEmpty(), "core.pwd.tmp must declare its effects")
        assertTrue(
            contract.descriptor.effects.contains(Effect.WRITES_WORKSPACE),
            "core.pwd.tmp MUST declare WRITES_WORKSPACE (the tmp directory is physically created)",
        )
        assertSame(ReplayPolicy.MEMOIZED, contract.descriptor.replayPolicy)
        assertTrue(contract.requiredCapabilities.isNotEmpty())
        assertTrue(
            contract.requiredCapabilities.any { it.key == TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY.key },
            "declared capability MUST include TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY",
        )
    }

    @Test
    fun `3 — input codec round-trip (empty envelope)`() {
        val codec: StepCodec<PwdTmpInput> = CorePwdTmpStep.definition.contract.inputCodec
        val encoded = codec.encode(PwdTmpInput)
        // The canonical envelope is the empty JSON object (mirrors core.isUnix).
        assertEquals("{}", encoded.value, "the durable envelope MUST be exactly `{}`")
        val decoded = codec.decode(encoded)
        assertSame(PwdTmpInput, decoded, "decode of empty envelope must return the singleton PwdTmpInput")
    }

    @Test
    fun `4 — output codec round-trip`() {
        val codec: StepCodec<PwdTmpOutput> = CorePwdTmpStep.definition.contract.outputCodec
        val output = PwdTmpOutput(path = "/tmp/junit-xxx/ctrl/workspace/TestStage-0/tmp")
        val encoded = codec.encode(output)
        val decoded = codec.decode(encoded)
        assertEquals(output, decoded)
    }

    @Test
    fun `5 — output codec rejects foreign kind`() {
        val codec: StepCodec<PwdTmpOutput> = CorePwdTmpStep.definition.contract.outputCodec
        // foreign envelope with kind = "pwd" (NOT "pwd.tmp") MUST be rejected.
        val foreign = EncodedStepValue("""{"kind":"pwd","path":"/some/path"}""")
        val ex = kotlin.runCatching { codec.decode(foreign) }.exceptionOrNull()
        assertNotNull(ex, "foreign kind MUST be rejected (the canonical kind discriminator is 'pwd.tmp')")
        assertTrue(
            ex!!.message!!.contains("pwd.tmp"),
            "rejection message must name the expected kind 'pwd.tmp': ${ex.message}",
        )
    }

    @Test
    fun `6 — capability declaration matches handler usage (G3-A4_2)`() {
        // The handler reaches ONLY TemporaryWorkspaceOperations (via
        // TemporaryWorkspaceOperationsAdapter); declaring more would over-promise
        // and break the architecture-fitness "declared capability == used capability" rule.
        val declared = CorePwdTmpStep.definition.contract.requiredCapabilities
        assertEquals(1, declared.size, "exactly one declared capability is required (handler uses exactly one)")
        assertEquals(
            TEMPORARY_WORKSPACE_OPERATIONS_CAPABILITY.key,
            declared.single().key,
            "declared key must match the used capability key",
        )
    }

    @Test
    fun `7 — handler is a StepHandler with typed I_O`() {
        // Compile-time check: if the handler signature drifts, this test fails to compile.
        val handler: StepHandler<PwdTmpInput, PwdTmpOutput> = CorePwdTmpStep.definition.handler
        assertNotNull(handler)
    }

    @Test
    fun `8 — registered through the open registry seam (no privileged core path)`() {
        val registry = dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry()
        CorePwdTmpStep.registerInto(registry)
        val resolved = registry.definition(CorePwdTmpStep.KEY)
        assertNotNull(resolved, "registry MUST resolve core.pwd.tmp through the open seam")
        assertEquals(CorePwdTmpStep.definition.contract.key, resolved!!.contract.key)
    }

    @Test
    fun `9 — registry key does not collide with any other registered Step`() {
        val registry = dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry()
        // Register the rest of the catalog (mirrors CoreStepRegistryFactory, minus core.pwd.tmp).
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
        CoreDeleteDirStep.registerInto(registry)
        CoreCleanWsStep.registerInto(registry)
        CoreMilestoneStep.registerInto(registry)
        CoreWaitUntilStep.registerInto(registry)
        CorePwdTmpStep.registerInto(registry)
        val resolved = registry.definition(CorePwdTmpStep.KEY)
        assertNotNull(resolved)
        assertEquals(CorePwdTmpStep.definition.contract.key, resolved!!.contract.key)
        // Sanity: core.pwd and core.pwd.tmp MUST both resolve as distinct keys.
        assertEquals(PluginStepId("core.pwd"), CorePwdStep.KEY, "core.pwd and core.pwd.tmp are distinct StepKeys")
    }

    @Test
    fun `10 — output is a TypedStepOutput (via the PwdOutput typealias)`() {
        val output: PwdTmpOutput = PwdTmpOutput(path = "/x")
        assertTrue(output is TypedStepOutput, "PwdTmpOutput (= PwdOutput) MUST implement TypedStepOutput for CommonExecutionBoundary")
    }

    @Test
    fun `11 — output codec decode shape (canonical envelope)`() {
        val codec: StepCodec<PwdTmpOutput> = CorePwdTmpStep.definition.contract.outputCodec
        // Sanity parse: the canonical envelope is {kind: "pwd.tmp", path: "..."}.
        val encoded = EncodedStepValue("""{"kind":"pwd.tmp","path":"/tmp/junit-123/ctrl/workspace/TestStage-0/tmp"}""")
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        assertEquals("pwd.tmp", obj["kind"]?.jsonPrimitive?.content)
        assertEquals("/tmp/junit-123/ctrl/workspace/TestStage-0/tmp", obj["path"]?.jsonPrimitive?.content)
        val decoded = codec.decode(encoded)
        assertEquals("/tmp/junit-123/ctrl/workspace/TestStage-0/tmp", decoded.path)
    }
}
