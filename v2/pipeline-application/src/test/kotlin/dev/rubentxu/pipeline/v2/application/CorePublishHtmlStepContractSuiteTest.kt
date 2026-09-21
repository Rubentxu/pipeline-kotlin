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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * StepContractSuite — WU-LPR-090 Phase B for `core.publishHTML` (Tier B #2).
 *
 * Test matrix (11 tests):
 * ```
 *  1. Step identity is core_publishHTML
 *  2. Contract completeness (key, descriptor, codecs, capabilities)
 *  3. Input codec round-trip (omitted flags preserved)
 *  4. Input codec rejects foreign kind
 *  5. Output codec round-trip (success)
 *  6. Output codec round-trip (failure)
 *  7. Output codec rejects foreign kind
 *  8. Capability declaration == usage (G3-A4.2: declared == used)
 *  9. Handler is a StepHandler typed I_O
 * 10. Registered through the open registry seam
 * 11. Registry key uniqueness vs other 19 CoreSteps (18 prior + 1 self)
 * ```
 *
 * Production-like registry-aware composition per AGENTS.md coordinator-test rule.
 */
class CorePublishHtmlStepContractSuiteTest {

    @Test
    fun `1 — Step identity is core_publishHTML`() {
        assertEquals(PluginStepId("core.publishHTML"), CorePublishHtmlStep.KEY)
    }

    @Test
    fun `2 — contract completeness (key, descriptor, codecs, capabilities)`() {
        val contract = CorePublishHtmlStep.definition.contract
        assertEquals(CorePublishHtmlStep.KEY, contract.key)
        assertNotNull(contract.descriptor, "descriptor is required")
        assertEquals("core.publishHTML", contract.descriptor.stepId)
        assertEquals(
            Effect.WRITES_WORKSPACE,
            contract.descriptor.effects.single(),
            "publishHTML MUST declare Effect.WRITES_WORKSPACE (filesystem archive write)",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            contract.descriptor.replayPolicy,
            "publishHTML MUST declare ReplayPolicy.MEMOIZED (overwrite on rerun)",
        )
        assertNotNull(contract.inputCodec, "input codec required")
        assertNotNull(contract.outputCodec, "output codec required")
        assertEquals(
            setOf(PUBLISH_HTML_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "publishHTML MUST declare exactly one required capability",
        )
    }

    @Test
    fun `3 — input codec round-trip (omitted flags preserved)`() {
        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = "build/reports",
            reportFiles = "**/*.html",
            keepAll = false,
            allowMissing = false,
            escapeUnderscores = false,
        )
        val encoded: EncodedStepValue = CorePublishHtmlStep.definition.contract.inputCodec.encode(input)
        val decoded: PublishHtmlInput = CorePublishHtmlStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded, "publishHTML input codec MUST round-trip losslessly (flags omitted when false)")
    }

    @Test
    fun `3b — input codec round-trip with all flags enabled`() {
        val input = PublishHtmlInput(
            name = "html-report",
            reportDir = "build/reports",
            reportFiles = "**/*.html",
            keepAll = true,
            allowMissing = true,
            escapeUnderscores = true,
        )
        val encoded: EncodedStepValue = CorePublishHtmlStep.definition.contract.inputCodec.encode(input)
        val decoded: PublishHtmlInput = CorePublishHtmlStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded, "publishHTML input codec MUST preserve all flags when enabled")
    }

    @Test
    fun `4 — input codec rejects foreign kind`() {
        val foreign = EncodedStepValue("{\"kind\":\"stash\",\"name\":\"x\",\"includes\":\"*\"}")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CorePublishHtmlStep.definition.contract.inputCodec.decode(foreign)
        }
        assertTrue(ex.message!!.contains("publishHTML"), "error message must mention the expected kind")
    }

    @Test
    fun `5 — output codec round-trip (success)`() {
        val out: TypedStepOutput = CorePublishHtmlStep.PublishHtmlOutput(
            publishedCount = 7,
            skipped = false,
            skipReason = null,
        )
        val encoded: EncodedStepValue = CorePublishHtmlStep.definition.contract.outputCodec.encode(out)
        val decoded: TypedStepOutput = CorePublishHtmlStep.definition.contract.outputCodec.decode(encoded)
        assertTrue(decoded is CorePublishHtmlStep.PublishHtmlOutput, "decoded MUST be PublishHtmlOutput")
        decoded as CorePublishHtmlStep.PublishHtmlOutput
        assertEquals(7, decoded.publishedCount)
        assertEquals(false, decoded.skipped)
        assertEquals(null, decoded.skipReason)
    }

    @Test
    fun `5b — output codec round-trip (skipped)`() {
        val out: TypedStepOutput = CorePublishHtmlStep.PublishHtmlOutput(
            publishedCount = 0,
            skipped = true,
            skipReason = PublishHtmlSkipReason.NO_FILES_MATCHED,
        )
        val encoded: EncodedStepValue = CorePublishHtmlStep.definition.contract.outputCodec.encode(out)
        val decoded: TypedStepOutput = CorePublishHtmlStep.definition.contract.outputCodec.decode(encoded)
        assertTrue(decoded is CorePublishHtmlStep.PublishHtmlOutput)
        decoded as CorePublishHtmlStep.PublishHtmlOutput
        assertEquals(true, decoded.skipped)
        assertEquals(PublishHtmlSkipReason.NO_FILES_MATCHED, decoded.skipReason)
    }

    @Test
    fun `6 — output codec round-trip (failure)`() {
        val out: TypedStepOutput = CorePublishHtmlStep.PublishHtmlFailureOutput(
            failureKind = dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT,
            message = "reportDir 'x' does not exist",
        )
        val encoded: EncodedStepValue = CorePublishHtmlStep.definition.contract.outputCodec.encode(out)
        val decoded: TypedStepOutput = CorePublishHtmlStep.definition.contract.outputCodec.decode(encoded)
        assertTrue(decoded is CorePublishHtmlStep.PublishHtmlFailureOutput)
        decoded as CorePublishHtmlStep.PublishHtmlFailureOutput
        assertEquals(dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT, decoded.failureKind)
        assertEquals("reportDir 'x' does not exist", decoded.message)
    }

    @Test
    fun `7 — output codec rejects foreign kind`() {
        val foreign = EncodedStepValue("{\"kind\":\"stash\",\"outcome\":\"FAILED\",\"failureKind\":\"SCRIPT\",\"message\":\"x\"}")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CorePublishHtmlStep.definition.contract.outputCodec.decode(foreign)
        }
        assertTrue(ex.message!!.contains("publishHTML"), "error message must mention the expected kind")
    }

    @Test
    fun `8 — capability declaration equals usage (G3-A4_2)`() {
        val contract = CorePublishHtmlStep.definition.contract
        assertEquals(
            setOf<StepCapability>(PUBLISH_HTML_OPERATIONS_CAPABILITY),
            contract.requiredCapabilities,
            "publishHTML MUST declare exactly PUBLISH_HTML_OPERATIONS_CAPABILITY (no broader)",
        )
    }

    @Test
    fun `9 — handler is a StepHandler typed I_O`() {
        val handler: StepHandler<PublishHtmlInput, TypedStepOutput> = CorePublishHtmlStep.definition.handler
        assertNotNull(handler, "handler must be a non-null StepHandler")
    }

    @Test
    fun `10 — registered through the open registry seam`() {
        val registry = InMemoryStepRegistry()
        CorePublishHtmlStep.registerInto(registry)
        val resolved = registry.definition(CorePublishHtmlStep.KEY)
        assertNotNull(resolved, "CorePublishHtmlStep MUST resolve via the open registry seam")
        assertEquals(CorePublishHtmlStep.definition, resolved)
    }

    @Test
    fun `11 — registry key uniqueness vs other 19 CoreSteps`() {
        val registry = CoreStepRegistryFactory.registry()
        // Registry contains 19 prior CoreSteps + CorePublishHtmlStep = 20.
        assertEquals(
            20,
            registry.keys().size,
            "factory registry must contain exactly 20 core StepDefinitions (19 prior + 1 self)",
        )
        val publishHtmlKeys = registry.keys().filter {
            it == CorePublishHtmlStep.KEY
        }
        assertEquals(
            1,
            publishHtmlKeys.size,
            "core.publishHTML MUST be unique in the registry (no duplicate StepKey)",
        )
    }

}
