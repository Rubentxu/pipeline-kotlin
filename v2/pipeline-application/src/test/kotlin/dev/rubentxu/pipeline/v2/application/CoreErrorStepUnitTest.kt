package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * S2-A1 / G1 — focused unit tests for the `core.error` registry seam.
 *
 * Scope (G1):
 *  - carrier invariant (CoreErrorOutput)
 *  - input codec round-trip
 *  - output codec round-trip
 *  - handler returns the carrier (no throw-as-control-flow)
 *  - descriptor preserves ABORTS_PIPELINE + NEVER
 *  - requiredCapabilities = emptySet()
 *  - StepDefinition is registered via registerInto (smoke)
 *
 * NOT in G1 (deferred to G2+):
 *  - CoreStepRegistryFactory wiring (G2)
 *  - parity vs legacy (G3)
 *  - legacy unreachable / removal (G5-G6)
 *  - full StepContractSuite (G7)
 *  - real scenario through installed distribution (G8)
 *
 * Counter invariants at G1:
 *  - LEGACY_PLUGIN_IDS == 12  (no removal yet)
 *  - metadata rows        == 12
 *  - dispatcher classes   == 12
 *
 * These tests only touch the new files; they MUST NOT delete or mutate any legacy
 * machinery. Asserted implicitly by not running any G4/G5 tests yet (G7 suite).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreErrorStepUnitTest {

    // ---------- carrier: CoreErrorOutput invariant ----------

    @Test
    fun `carrier invariant -- outcome equals Failure(failure)`() {
        val failure = PipelineFailure(FailureKind.USER, "boom")
        val output = CoreErrorOutput.from(failure)
        assertEquals(StepOutcome.Failure(failure), output.outcome,
            "outcome MUST equal StepOutcome.Failure(failure)")
        assertSame(failure, output.failure,
            "carrier MUST hold the same PipelineFailure instance (no parallel reconstruction)")
    }

    @Test
    fun `carrier invariant -- TypedStepOutput marker is implemented`() {
        val output = CoreErrorOutput.from(PipelineFailure(FailureKind.USER, "boom"))
        // The boundary uses `produced as? TypedStepOutput` to project `outcome`.
        // This is the Step-agnostic mechanism; the coordinator must NEVER branch on
        // `core.error`. The carrier MUST implement the marker for the projection to fire.
        assertTrue(output is TypedStepOutput,
            "CoreErrorOutput MUST implement TypedStepOutput so CommonExecutionBoundary can project outcome")
    }

    @Test
    fun `carrier factory -- of(kind, message) is equivalent to from(PipelineFailure(kind, message))`() {
        val a = CoreErrorOutput.of(FailureKind.USER, "boom")
        val b = CoreErrorOutput.from(PipelineFailure(FailureKind.USER, "boom"))
        assertEquals(b.failure, a.failure)
        assertEquals(b.outcome, a.outcome)
    }

    @Test
    fun `carrier invariant violation -- direct constructor with mismatched outcome is rejected`() {
        val failure = PipelineFailure(FailureKind.USER, "boom")
        // Direct construction with Success outcome MUST fail the invariant check.
        // The only authority for CoreErrorOutput is `from`/`of`; hand-rolled values
        // that violate the invariant are rejected at construction.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CoreErrorOutput(failure, StepOutcome.Success)
        }
        assertTrue("invariant" in ex.message!!,
            "constructor rejection must cite the invariant")
    }

    @Test
    fun `core_error input -- blank message is rejected (PipelineFailure invariant)`() {
        // PipelineFailure enforces `require(message.isNotBlank())`; the typed input
        // also pre-validates via init {} so handlers receive a valid carrier.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CoreErrorInput(message = "", failureKind = FailureKind.USER)
        }
        assertTrue("blank" in ex.message!!.lowercase())
    }

    // ---------- input codec ----------

    @Test
    fun `input codec -- round-trip preserves message and failureKind`() {
        val input = CoreErrorInput(message = "boom", failureKind = FailureKind.USER)
        val encoded = CoreErrorStep.definition.contract.inputCodec.encode(input)
        val decoded = CoreErrorStep.definition.contract.inputCodec.decode(encoded)
        assertEquals(input, decoded)
    }

    @Test
    fun `input codec -- encoded envelope is byte-identical to legacy decoder output`() {
        // G3 parity: the dsl-v1 envelope MUST equal what `CanonicalCoreStepDecoder`
        // produces for `core.error` so durable fingerprint/journal identity is preserved
        // across the migration. Legacy shape: `{"kind":"error","message":"...","failureKind":"..."}`.
        val input = CoreErrorInput(message = "test error message", failureKind = FailureKind.USER)
        val encoded = CoreErrorStep.definition.contract.inputCodec.encode(input).value
        assertEquals(
            """{"kind":"error","message":"test error message","failureKind":"USER"}""",
            encoded,
            "encoded input must be byte-identical to legacy decoder envelope",
        )
    }

    @Test
    fun `input codec -- unknown failureKind name fails closed`() {
        val bad = """{"kind":"error","message":"x","failureKind":"MADE_UP"}"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CoreErrorStep.definition.contract.inputCodec.decode(dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(bad))
        }
        assertTrue("Unknown failure kind" in ex.message!!)
    }

    @Test
    fun `input codec -- wrong kind discriminant fails closed`() {
        val bad = """{"kind":"shell","message":"x","failureKind":"USER"}"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CoreErrorStep.definition.contract.inputCodec.decode(dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(bad))
        }
        assertTrue("kind" in ex.message!!.lowercase())
    }

    // ---------- output codec ----------

    @Test
    fun `output codec -- round-trip preserves failure (deterministic)`() {
        val failure = PipelineFailure(FailureKind.USER, "boom")
        val output = CoreErrorOutput.from(failure)
        val encoded = CoreErrorStep.definition.contract.outputCodec.encode(output)
        val decoded = CoreErrorStep.definition.contract.outputCodec.decode(encoded)
        assertEquals(output.failure, decoded.failure)
        assertEquals(output.outcome, decoded.outcome)
    }

    @Test
    fun `output codec -- envelope shape carries only failureKind + failureMessage + kind`() {
        val output = CoreErrorOutput.of(FailureKind.USER, "boom")
        val encoded = CoreErrorStep.definition.contract.outputCodec.encode(output).value
        // No Throwable serialization; no stack traces; no extra fields.
        assertFalse("stackTrace" in encoded, "must not serialize stack traces")
        assertFalse("cause" in encoded, "must not serialize Throwable cause")
        assertFalse("exceptionClass" in encoded, "must not serialize exception class")
        // Required fields are present.
        assertTrue(""""kind":"error"""" in encoded)
        assertTrue(""""failureKind":"USER"""" in encoded)
        assertTrue(""""failureMessage":"boom"""" in encoded)
    }

    @Test
    fun `output codec decodes failure kind and message`() {
        // The output codec does NOT serialize `outcome` directly; it reconstructs it via
        // CoreErrorOutput.from(PipelineFailure(...)) which is the single failure authority.
        // Decode must produce the same carrier shape (failure, outcome == Failure(failure)).
        val encoded = CoreErrorStep.definition.contract.outputCodec
            .encode(CoreErrorOutput.of(FailureKind.SCRIPT, "boom"))
            .value
        val decoded = CoreErrorStep.definition.contract.outputCodec
            .decode(dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(encoded))
        assertEquals(FailureKind.SCRIPT, decoded.failure.kind)
        assertEquals("boom", decoded.failure.message)
        assertEquals(StepOutcome.Failure(decoded.failure), decoded.outcome)
    }

    // ---------- handler ----------

    @Test
    fun `handler -- returns carrier, does NOT throw to signal failure`() {
        val input = CoreErrorInput(message = "boom", failureKind = FailureKind.USER)
        val ctx = StepHandlerContext(
            runId = dev.rubentxu.pipeline.v2.domain.RunId("test-run"),
            stepIndex = 0,
            capabilities = EmptyCapabilityAccess,
        )
        val produced = runBlocking {
            CoreErrorStep.definition.handler.execute(input, ctx)
        }
        // The handler returns the typed carrier; the boundary reads `outcome` from it.
        assertTrue(produced is CoreErrorOutput,
            "handler MUST return CoreErrorOutput (TypedStepOutput); no exception-as-control-flow")
        assertEquals(FailureKind.USER, produced.failure.kind)
        assertEquals("boom", produced.failure.message)
        assertEquals(StepOutcome.Failure(produced.failure), produced.outcome)
    }

    @Test
    fun `handler -- all FailureKind variants produce equivalent carriers`() {
        // The handler is value-trivial (no side-effects). It must work for every
        // FailureKind variant uniformly.
        for (kind in FailureKind.entries) {
            val input = CoreErrorInput(message = "msg-$kind", failureKind = kind)
            val ctx = StepHandlerContext(
                runId = dev.rubentxu.pipeline.v2.domain.RunId("test"),
                stepIndex = 0,
                capabilities = EmptyCapabilityAccess,
            )
            val out = runBlocking { CoreErrorStep.definition.handler.execute(input, ctx) }
            assertEquals(kind, out.failure.kind)
            assertEquals("msg-$kind", out.failure.message)
            assertEquals(StepOutcome.Failure(out.failure), out.outcome)
        }
    }

    // ---------- descriptor & contract ----------

    @Test
    fun `descriptor -- preserves ABORTS_PIPELINE and NEVER from legacy metadata`() {
        val d = CoreErrorStep.definition.contract.descriptor
        assertEquals("core.error", d.stepId)
        assertEquals("error", d.name)
        assertEquals(listOf(Effect.ABORTS_PIPELINE), d.effects,
            "core.error MUST carry Effect.ABORTS_PIPELINE (matches legacy metadata row)")
        assertEquals(ReplayPolicy.NEVER, d.replayPolicy,
            "core.error MUST carry ReplayPolicy.NEVER (justified by its semantics, not copied)")
    }

    @Test
    fun `descriptor -- location is CONTROLLER (matches legacy in-controller execution)`() {
        val d = CoreErrorStep.definition.contract.descriptor
        assertEquals(dev.rubentxu.pipeline.v2.domain.ExecutionLocation.CONTROLLER, d.executionLocation)
    }

    @Test
    fun `contract -- requiredCapabilities is empty`() {
        // G0 audit: the handler does not reach a coordinator, journal, event sink, or
        // process executor. It returns a typed outcome; the boundary owns event projection.
        assertTrue(CoreErrorStep.definition.contract.requiredCapabilities.isEmpty(),
            "core.error MUST declare no capabilities; no fictional capability")
    }

    @Test
    fun `identity -- KEY equals PluginStepId(core-error)`() {
        assertEquals(dev.rubentxu.pipeline.v2.domain.PluginStepId("core.error"), CoreErrorStep.KEY)
    }

    // ---------- registerInto smoke (registry seam proof) ----------

    @Test
    fun `registerInto -- registry resolves core error to the new StepDefinition`() {
        val registry: StepRegistry = InMemoryStepRegistry().also { CoreErrorStep.registerInto(it) }
        val resolved = registry.definition(dev.rubentxu.pipeline.v2.domain.PluginStepId("core.error"))
        assertNotNull(resolved, "registry MUST resolve core.error to CoreErrorStep.definition")
        assertSame(CoreErrorStep.definition, resolved)
    }

    @Test
    fun `registerInto -- duplicate registration fails closed`() {
        // The registry MUST be fail-closed on duplicate `core.error` keys. Mirrors the
        // Step Constitution law (duplicate StepKey → fail closed, never "first/last wins").
        val registry = InMemoryStepRegistry().also { CoreErrorStep.registerInto(it) }
        assertThrows(IllegalArgumentException::class.java) {
            CoreErrorStep.registerInto(registry)
        }
    }
}

/** Empty capability access for handler unit tests; mirrors `MapAccess(emptyMap())` from the domain tests. */
private object EmptyCapabilityAccess : StepCapabilityAccess {
    override fun available(): Set<StepCapability> = emptySet()
    override fun <T : Any> get(key: StepCapability): T =
        throw IllegalArgumentException("capability unavailable: $key")
}
