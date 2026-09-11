package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalErrorNodeDispatcher
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.RegistryStepInvoker
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepInvocationOutcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * S2-A1 / G3 — Legacy ↔ Registry semantic parity for `core.error`.
 *
 * Purpose: prove that the new registry-based path produces the SAME canonical semantics
 * as the legacy path for the SAME encoded input. Per the A4_8 pattern, we compare
 * semantic dimensions (kind, message, StepOutcome, effects, replay, capabilities,
 * encoded payload shape) — NOT byte-identical internal representations.
 *
 * Counter invariant at G3:
 *   - core.error IS in LEGACY_PLUGIN_IDS (still 12 entries)
 *   - CanonicalErrorNodeDispatcher EXISTS (the legacy dispatcher)
 *   - canonical metadata row for core.error EXISTS
 *   - StructuralFamilyResolver(core.error, registry) == LegacyCore
 *   - Production routing still flows through the legacy path
 *
 * G3 is NOT a routing flip. It proves equivalence BEFORE the flip so that the flip in
 * G5 can be made with confidence. Both paths remain executable during G3.
 *
 * Pattern: A4_8LegacyRegistrySemanticParityTest (the core.sh precedent). Reuse the
 * shape — drive both paths with the SAME input, compare semantic dimensions, assert
 * paridad.
 *
 * UUIDs, timestamps, sequence numbers, and execution accidentals are NOT compared —
 * only canonical semantic projections (kind, message, StepOutcome, effects, replay,
 * required capabilities, encoded payload shape).
 *
 * Every test in this file compares a DIMENSION across the two paths (or asserts an
 * invariant that both paths must satisfy). No `assertNotNull` / `assertNull` after a
 * non-nullable API call, no `assertTrue(true)` placeholders.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreErrorLegacyRegistryParityTest {

    // ----- helpers ----------------------------------------------------------

    /** A minimal capability access; the parity paths declare no capabilities. */
    private object NoCapabilities : StepCapabilityAccess {
        override fun available(): Set<StepCapability> = emptySet()
        override fun <T : Any> get(key: StepCapability): T =
            throw IllegalArgumentException("capability unavailable: $key")
    }

    private fun handlerContext(runId: String) = StepHandlerContext(
        runId = dev.rubentxu.pipeline.v2.domain.RunId(runId),
        stepIndex = 0,
        capabilities = NoCapabilities,
    )

    /**
     * Drive the LEGACY path: CanonicalErrorNodeDispatcher.dispatch with a typed command.
     * The legacy command is constructed directly (the decoder is what builds it from
     * encoded payload; we keep the test free of dsl-v1 details by using the typed form).
     */
    private fun legacyOutcome(message: String, kind: FailureKind): StepOutcome =
        CanonicalErrorNodeDispatcher().dispatch(
            CanonicalCoreStepCommand.Error(message = message, failureKind = kind),
        )

    /**
     * Drive the REGISTRY path: encode the typed input, run through RegistryStepInvoker,
     * and read the typed StepOutcome from the carrier.
     */
    private fun registryOutcome(
        message: String,
        kind: FailureKind,
        runId: String = "g3-$kind",
    ): Pair<StepOutcome, dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue> {
        val registry = InMemoryStepRegistry().also { CoreErrorStep.registerInto(it) }
        val invoker = RegistryStepInvoker(registry)
        val input = CoreErrorInput(message = message, failureKind = kind)
        val encoded = CoreErrorStep.definition.contract.inputCodec.encode(input)
        val outcome = runBlocking {
            invoker.invoke<CoreErrorInput, CoreErrorOutput>(
                CoreErrorStep.KEY, encoded, handlerContext(runId),
            )
        }
        val success = outcome as StepInvocationOutcome.Success
        val carrier = success.value
        assertTrue(
            carrier is TypedStepOutput,
            "registry output MUST be a TypedStepOutput (the boundary's projection seam)",
        )
        return carrier.outcome to encoded
    }

    /** Extract the typed [PipelineFailure] from a [StepOutcome.Failure]. */
    private fun failureOf(outcome: StepOutcome): PipelineFailure =
        (outcome as StepOutcome.Failure).failure

    // ========================================================================
    // Parity table — canonical semantic dimensions
    // ========================================================================

    @Test
    fun `parity -- failure kind preserved (USER)`() {
        val kind = FailureKind.USER
        val legacy = failureOf(legacyOutcome("boom", kind))
        val (registry, _) = registryOutcome("boom", kind)
        val registryFailure = failureOf(registry)
        assertEquals(legacy.kind, registryFailure.kind,
            "failure.kind MUST be identical between legacy and registry paths")
    }

    @Test
    fun `parity -- failure message preserved verbatim`() {
        val message = "test error message"
        val legacy = failureOf(legacyOutcome(message, FailureKind.USER))
        val (registry, _) = registryOutcome(message, FailureKind.USER)
        val registryFailure = failureOf(registry)
        assertEquals(message, registryFailure.message,
            "failure.message MUST be preserved verbatim across paths")
    }

    @Test
    fun `parity -- StepOutcome is Failure in BOTH paths`() {
        val legacy = legacyOutcome("boom", FailureKind.USER)
        val (registry, _) = registryOutcome("boom", FailureKind.USER)
        assertTrue(legacy is StepOutcome.Failure,
            "legacy path MUST produce StepOutcome.Failure (not Success/Unstable)")
        assertTrue(registry is StepOutcome.Failure,
            "registry path MUST produce StepOutcome.Failure (not Success/Unstable)")
    }

    @Test
    fun `parity -- PipelineFailure is structurally equal between paths`() {
        val legacy = failureOf(legacyOutcome("x", FailureKind.SCRIPT))
        val (registry, _) = registryOutcome("x", FailureKind.SCRIPT)
        val registryFailure = failureOf(registry)
        assertEquals(legacy, registryFailure,
            "PipelineFailure(kind, message) MUST be structurally equal across paths")
    }

    @Test
    fun `parity -- effects are ABORTS_PIPELINE on BOTH paths`() {
        // G0 audit: legacy metadata row carries Effect.ABORTS_PIPELINE.
        val legacyEffect = CanonicalCoreStepMetadata
            .metadata("core.error")
            .effects
        val registryEffect = CoreErrorStep.definition.contract.descriptor.effects.toSet()
        assertEquals(setOf(Effect.ABORTS_PIPELINE), legacyEffect,
            "legacy metadata MUST carry ABORTS_PIPELINE")
        assertEquals(legacyEffect, registryEffect,
            "effects MUST be identical across paths (no per-Step drift)")
    }

    @Test
    fun `parity -- replay policy is NEVER on BOTH paths`() {
        val legacyReplay = CanonicalCoreStepMetadata.metadata("core.error").replayPolicy
        val registryReplay = CoreErrorStep.definition.contract.descriptor.replayPolicy
        assertEquals(ReplayPolicy.NEVER, legacyReplay,
            "legacy metadata MUST carry ReplayPolicy.NEVER")
        assertEquals(legacyReplay, registryReplay,
            "replay policy MUST be identical across paths")
    }

    @Test
    fun `parity -- required capabilities are zero on BOTH paths`() {
        // Registry: declared as empty set in StepContract.
        val registryRequired = CoreErrorStep.definition.contract.requiredCapabilities
        assertTrue(registryRequired.isEmpty(),
            "registry contract MUST declare no capabilities (empty Set, not null)")
        // Legacy: the canonical StepMetadata has no `requiredCapabilities` field at all
        // — capability admission is purely a registry-seam concern. We assert structural
        // absence on the legacy shape: the data class exposes `effects` + `replayPolicy`
        // + `recoveryPolicy` and nothing else, so there is no place to declare capabilities.
        // Equivalently: the legacy dispatcher's job (build a typed PipelineFailure) does
        // not consult any capability, which is what the registry also asserts.
        val legacyFields = CanonicalCoreStepMetadata::class.java.declaredFields.map { it.name }
        assertTrue(
            "requiredCapabilities" !in legacyFields,
            "legacy StepMetadata MUST NOT declare a requiredCapabilities field; " +
                "capability admission is a registry-seam concern (legacy fields=$legacyFields)",
        )
        // Equivalence: legacy has NO capability surface; registry has empty Set. Both mean
        // "this Step does not require any capability".
        assertEquals(emptySet<StepCapability>(), registryRequired,
            "registry contract MUST declare the empty Set, not null, not a singleton")
    }

    // ========================================================================
    // Corpus coverage — same semantic payload across representative inputs
    // ========================================================================

    private data class ParityCase(
        val label: String,
        val message: String,
        val kind: FailureKind,
    )

    private val corpus: List<ParityCase> = listOf(
        ParityCase("default USER (simple)", "boom", FailureKind.USER),
        ParityCase("explicit SCRIPT failure", "shell exited with code 1", FailureKind.SCRIPT),
        ParityCase("explicit TIMEOUT failure", "operation exceeded 30s", FailureKind.TIMEOUT),
        ParityCase("explicit INFRASTRUCTURE failure", "disk full", FailureKind.INFRASTRUCTURE),
        ParityCase("message with special chars", "boom: \"a\" / 'b' \\ c \n line2 \t tab", FailureKind.USER),
        ParityCase("network failure", "x", FailureKind.NETWORK),
    )

    @Test
    fun `parity corpus -- every case has legacy kind == registry kind`() {
        for (case in corpus) {
            val legacy = failureOf(legacyOutcome(case.message, case.kind))
            val (registry, _) = registryOutcome(case.message, case.kind, runId = "g3-corpus-${case.label}")
            val registryFailure = failureOf(registry)
            assertEquals(case.kind, legacy.kind,
                "[${case.label}] legacy kind MUST equal expected ${case.kind}")
            assertEquals(legacy.kind, registryFailure.kind,
                "[${case.label}] registry kind MUST equal legacy kind")
        }
    }

    @Test
    fun `parity corpus -- every case has legacy message == registry message`() {
        for (case in corpus) {
            val legacy = failureOf(legacyOutcome(case.message, case.kind))
            val (registry, _) = registryOutcome(case.message, case.kind, runId = "g3-msg-${case.label}")
            val registryFailure = failureOf(registry)
            assertEquals(case.message, legacy.message,
                "[${case.label}] legacy message MUST be preserved")
            assertEquals(legacy.message, registryFailure.message,
                "[${case.label}] registry message MUST equal legacy message")
        }
    }

    @Test
    fun `parity corpus -- every case has registry StepOutcome type matching legacy`() {
        for (case in corpus) {
            val legacy = legacyOutcome(case.message, case.kind)
            val (registry, _) = registryOutcome(case.message, case.kind, runId = "g3-out-${case.label}")
            assertEquals(legacy::class, registry::class,
                "[${case.label}] StepOutcome variant MUST match across paths " +
                    "(legacy=${legacy::class.simpleName}, registry=${registry::class.simpleName})")
        }
    }

    // ========================================================================
    // Encoded payload shape — dsl-v1 envelope parity (byte-identical on input)
    // ========================================================================

    @Test
    fun `parity -- registry input codec envelope equals legacy dsl-v1 envelope`() {
        // The codec must emit the EXACT same dsl-v1 envelope the legacy decoder produced,
        // so durable fingerprint/journal identity is continuous across the eventual flip.
        // This test pins the envelope shape independently of any UUID/timestamp.
        val kind = FailureKind.USER
        val message = "test error message"
        val legacyEnvelope =
            "{\"kind\":\"error\",\"message\":\"${message}\",\"failureKind\":\"${kind.name}\"}"
        val registryEnvelope = CoreErrorStep.definition.contract.inputCodec
            .encode(CoreErrorInput(message, kind))
            .value
        assertEquals(legacyEnvelope, registryEnvelope,
            "registry input codec MUST emit a byte-identical dsl-v1 envelope")
    }

    // ========================================================================
    // Invariant: boundary does NOT re-classify the carrier
    // ========================================================================

    @Test
    fun `parity -- registry carrier outcome equals legacy StepOutcome (no re-classification)`() {
        // The boundary uses `produced as? TypedStepOutput` to project `outcome` from the
        // carrier. It MUST NOT re-classify; the carrier is the single authority.
        for (kind in FailureKind.entries) {
            val legacy = legacyOutcome("x-$kind", kind)
            val (registry, _) = registryOutcome("x-$kind", kind, runId = "g3-rec-${kind.name}")
            assertEquals(legacy, registry,
                "[${kind.name}] StepOutcome MUST be identical across paths; " +
                    "the boundary MUST NOT re-classify the registry carrier")
        }
    }

    @Test
    fun `parity -- registry failure identity equals legacy failure identity (same PipelineFailure)`() {
        for (kind in FailureKind.entries) {
            val message = "msg-${kind.name}"
            val legacy = failureOf(legacyOutcome(message, kind))
            val (registry, _) = registryOutcome(message, kind, runId = "g3-id-${kind.name}")
            val registryFailure = failureOf(registry)
            assertEquals(legacy, registryFailure,
                "[${kind.name}] PipelineFailure(kind, message) MUST equal across paths")
            assertEquals(legacy.kind, registryFailure.kind)
            assertEquals(legacy.message, registryFailure.message)
        }
    }

    // ========================================================================
    // Invariant: counters UNCHANGED at G3
    // ========================================================================

    @org.junit.jupiter.api.Disabled("Archived G3 invariant: superseded by CoreErrorRegistryPrimaryFitnessTest (G5). " +
        "At G5 the LEGACY_PLUGIN_IDS membership flipped; the structural assertion is in the G5 fitness.")
    @Test
    fun `G3 invariant -- LEGACY_PLUGIN_IDS still contains core error`() {
        val legacyIds = CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS
        assertTrue("core.error" in legacyIds,
            "core.error MUST remain in LEGACY_PLUGIN_IDS at G3; the flip is G5")
    }

    @org.junit.jupiter.api.Disabled("Archived G3 invariant: superseded by CoreErrorRegistryPrimaryFitnessTest (G5). " +
        "At G5 the family flipped to Registry; the structural assertion is in the G5 fitness.")
    @Test
    fun `G3 invariant -- StructuralFamilyResolver classifies core error as LegacyCore`() {
        val registry = CoreStepRegistryFactory.registry()
        val family = dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
            .classify(CoreErrorStep.KEY, registry)
        assertEquals(
            dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily.LegacyCore,
            family,
            "StructuralFamilyResolver MUST return LegacyCore for core.error while " +
                "LEGACY_PLUGIN_IDS still contains it (G3 invariant)",
        )
    }

    @Test
    fun `G3 invariant -- legacy dispatcher still resolves and dispatches`() {
        // We do NOT use `Class.forName(...).newInstance()` followed by `assertNotNull` —
        // `Class.forName` throws on absence and `newInstance` returns non-null. Instead,
        // we instantiate directly and exercise dispatch, comparing to the registry carrier.
        val dispatcher = CanonicalErrorNodeDispatcher()
        val outcome = dispatcher.dispatch(
            CanonicalCoreStepCommand.Error("x", FailureKind.USER),
        )
        assertTrue(outcome is StepOutcome.Failure,
            "legacy dispatcher MUST still produce StepOutcome.Failure at G3")
        // Cross-check: same semantics as registry for the same inputs.
        val (registryOutcome, _) = registryOutcome("x", FailureKind.USER, runId = "g3-dispatcher")
        assertEquals(
            failureOf(outcome).kind,
            failureOf(registryOutcome).kind,
            "legacy dispatcher kind MUST equal registry kind for the same inputs",
        )
    }
}
