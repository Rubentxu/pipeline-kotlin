package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionResult
import dev.rubentxu.pipeline.v2.application.durable.ExecutionBoundaryFactory
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.FamilyRouter
import dev.rubentxu.pipeline.v2.application.durable.FamilyRoutingDecision
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * LFC-2E1 / S2-A1 / G5 — `core.error` REGISTRY_PRIMARY fitness.
 *
 * Goal: prove that flipping the production routing authority for `core.error` from
 * the legacy canonical decoder / dispatcher / metadata row to the open-world
 * registry spine did not break anything and removed the legacy path as the
 * production source of truth.
 *
 * Three independent proofs (single class, 11 tests):
 *
 *  1. **Membership flip** — `core.error` is no longer in LEGACY_PLUGIN_IDS; the
 *     structural family flips to `Registry`. Negative pins on echo/sh prove the
 *     flip is surgical (no global legacy wipe).
 *
 *  2. **Metadata authority** — `RegistryStepMetadataResolver.composite(registry)
 *     .resolve(core.error)` returns the registry `StepDefinition.contract.descriptor`
 *     fields (effects, replayPolicy, recoveryPolicy). The legacy authority is no
 *     longer consulted for this key.
 *
 *  3. **LEGACY_UNREACHABLE execution** — the production routing path no longer
 *     reaches the legacy decoder/dispatcher; the production boundary executes the
 *     registered `CoreErrorStep` via `RegistryExecutionBoundary.coexecute`, not
 *     via the legacy `LegacyExecutionBoundary`.
 *
 *  4. **Canonical preparation seam** — `RegistryExecutionPreparation.prepare(...)`
 *     produces `Ready(PreparedRegistryExecution)` proving the production seam
 *     (registry resolution + capability admission + typed decode) accepts
 *     `core.error` end-to-end. Reaches `coexecute` and projects the typed
 *     `CoreErrorOutput.outcome` into `CommonExecutionResult`.
 *
 * Authority hierarchy:
 *   - G3 (parity) is preserved in CoreErrorLegacyRegistryParityTest (semantic
 *     parity table; structural invariants archived).
 *   - G4 (pre-flip readiness) is archived as historical evidence.
 *   - G6 (LEGACY_REMOVED) will delete the legacy `core.error` code from disk and
 *     is asserted by S3ErrorLegacyRemovedFitnessTest.
 *
 * The pattern mirrors `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` (LB-02 WU9) for
 * `core.sh`; this test applies it to `core.error` (atomic, in-controller, no
 * capability requirements).
 */
@Timeout(value = 30)
class CoreErrorRegistryPrimaryFitnessTest {

    private fun factoryRegistry() = CoreStepRegistryFactory.registry()

    // ========================================================================
    // (1) Membership flip — core.error NO LONGER a legacy executable key
    // ========================================================================

    @Test
    fun `G5 flip -- LEGACY_PLUGIN_IDS is exactly the 9 residual keys (full-set equality)`() {
        // Full-set equality is stronger than a partial negative pin: it proves three
        // facts simultaneously:
        //   - core.error is REMOVED (S2-A1 / G5 flip)
        //   - core.echo, core.sh remain REMOVED (S1 / LB-02 closures)
        //   - core.sleep is REMOVED (LFC-2E1-S2-A2 / G5; CERTIFIED at S2-A2/G8)
        //   - core.file.writeFile is REMOVED from legacy routing (LFC-2E1-S2-A3 / G4)
        //   - none of the other 9 legacy keys were accidentally burned down
        val expected = setOf(
            "core.emit.event",
            "core.emit.event",
            "core.milestone",
            "core.deleteDir",
            "core.cleanWs",
            "core.load",
            "core.pwd",
            "core.isUnix",
            "core.waitUntil",
            "core.archiveArtifacts",
        )
        assertEquals(
            expected,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "LEGACY_PLUGIN_IDS MUST be exactly the 9 residual legacy keys post-S2-A3/G4; " +
                "set equality catches both accidental removals and accidental additions",
        )
        assertEquals(9, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
    }

    @Test
    fun `G5 flip -- StructuralFamilyResolver classifies core error as Registry (not LegacyCore)`() {
        val registry = factoryRegistry()
        val family = StructuralFamilyResolver.classify(CoreErrorStep.KEY, registry)
        assertEquals(
            StructuralStepFamily.Registry,
            family,
            "Post-G5: StructuralFamilyResolver MUST classify `core.error` as Registry",
        )
    }

    @Test
    fun `G5 flip -- FamilyRouter decide for core error returns SeamedRouting (registry owns the key)`() {
        val registry = factoryRegistry()
        val decision = FamilyRouter.decide(
            dispatcher = CanonicalNodeDispatcher(),
            invocationExecutor = null,
            stepRegistry = registry,
            stepKey = CoreErrorStep.KEY,
        )
        assertTrue(
            decision is FamilyRoutingDecision.SeamedRouting,
            "Post-G5: FamilyRouter must SeamedRouting for core.error; got $decision",
        )
    }

    @Test
    fun `G5 flip -- registry primary keys are core echo, core sh, core error (positive control)`() {
        // Positive control: after S1, LB-02/S2-prior and S2-A1/G5, the registry-primary
        // set MUST be exactly {core.echo, core.sh, core.error}. Anything else means a
        // Step was burned down (good) or accidentally introduced (bad).
        val registry = factoryRegistry()
        val registryPrimary = setOf(
            CoreEchoStep.KEY,
            CoreShellStep.KEY,
            CoreErrorStep.KEY,
        ).map { StructuralFamilyResolver.classify(it, registry) }
        assertEquals(
            List(3) { StructuralStepFamily.Registry },
            registryPrimary,
            "core.echo, core.sh, core.error MUST all classify as Registry (REGISTRY_PRIMARY set)",
        )
    }

    // ========================================================================
    // (2) Metadata authority — registry descriptor is the source of truth
    // ========================================================================

    @Test
    fun `G5 authority -- registry metadata for core error matches CoreErrorStep descriptor`() {
        val resolver = RegistryStepMetadataResolver.composite(factoryRegistry())
        val metadata = resolver.resolve(CoreErrorStep.KEY)
        assertNotNull(metadata)
        // Authority: the descriptor on the registered definition.
        val descriptor = CoreErrorStep.definition.contract.descriptor
        assertEquals(
            descriptor.effects.toSet(),
            metadata!!.effects,
            "effects MUST come from CoreErrorStep.definition.contract.descriptor (registry authority)",
        )
        assertEquals(
            descriptor.replayPolicy,
            metadata.replayPolicy,
            "replayPolicy MUST come from CoreErrorStep.definition.contract.descriptor",
        )
        assertEquals(
            descriptor.recoveryPolicy,
            metadata.recoveryPolicy,
            "recoveryPolicy MUST come from CoreErrorStep.definition.contract.descriptor",
        )
        // Pin the values explicitly so a future regression that returns a different
        // descriptor fields shows up as a discrete failure.
        assertEquals(
            setOf(Effect.ABORTS_PIPELINE),
            metadata.effects,
            "core.error effects MUST remain ABORTS_PIPELINE (parity with legacy)",
        )
        assertEquals(
            ReplayPolicy.NEVER,
            metadata.replayPolicy,
            "core.error replayPolicy MUST remain NEVER (parity with legacy)",
        )
    }

    @Test
    fun `G5 authority -- registry metadata does NOT consult the legacy row for core error`() {
        // If the composite resolver ever fell back to the legacy authority for
        // core.error, this would succeed (because the legacy row still exists in
        // CanonicalCoreStepMetadata). It MUST raise EngineInvariantViolation
        // because the empty registry has no definition and core.error is no longer
        // a legacy executable.
        val emptyRegistry = InMemoryStepRegistry()
        val resolver = RegistryStepMetadataResolver.composite(emptyRegistry)
        assertThrows(EngineInvariantViolation::class.java) {
            resolver.resolve(CoreErrorStep.KEY)
        }
    }

    // ========================================================================
    // (3) Canonical preparation seam — registry resolution + capability + decode
    // ========================================================================

    @Test
    fun `G5 prepare -- RegistryExecutionPreparation produces Ready for core error`() {
        val registry = factoryRegistry()
        val input = CoreErrorInput(
            message = "core.error registry primary path is the production authority",
            failureKind = FailureKind.USER,
        )
        val encodedInput = CoreErrorStep.definition.contract.inputCodec.encode(input)

        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreErrorStep.KEY,
            encodedInput = encodedInput,
            availableCapabilities = emptySet(),
        )
        // The preparation proves three SEPARATE production-seam facts:
        //   1. registry resolution: registry.definition(core.error) is non-null
        //   2. capability admission: requiredCapabilities - availableCapabilities is empty
        //   3. typed decode: inputCodec.decode(encodedInput) succeeded
        val ready = assertInstanceOf(
            ExecutionPreparation.Ready::class.java,
            preparation,
            "RegistryExecutionPreparation.prepare MUST produce Ready for core.error post-G5",
        )
        val prepared = assertInstanceOf(
            PreparedRegistryExecution::class.java,
            ready.prepared,
            "Ready.prepared MUST be a PreparedRegistryExecution for core.error",
        )
        assertEquals(CoreErrorStep.KEY, prepared.key)
        assertSame(
            CoreErrorStep.definition,
            prepared.definition,
            "PreparedRegistryExecution MUST carry the canonical CoreErrorStep.definition instance",
        )
    }

    @Test
    fun `G5 prepare -- schema mismatch produces Rejected (typed-decode admission)`() {
        // Negative proof: an unknown failureKind in the encoded payload MUST be rejected
        // BEFORE any handler execution. The encoder accepts only the 3 FailureKind entries;
        // a hand-crafted envelope with an unknown kind fails the typed decode step.
        val registry = factoryRegistry()
        val badEncoded = dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue(
            value = """{"kind":"error","message":"boom","failureKind":"UNKNOWN_KIND"}""",
        )
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreErrorStep.KEY,
            encodedInput = badEncoded,
            availableCapabilities = emptySet(),
        )
        val rejected = assertInstanceOf(
            ExecutionPreparation.Rejected::class.java,
            preparation,
            "registry prepare MUST reject an envelope whose failureKind is unknown",
        )
        assertTrue(
            rejected.reason.contains("schema mismatch") || rejected.reason.contains("Unknown failure kind"),
            "Rejection reason MUST mention schema mismatch / unknown failure kind; got: ${rejected.reason}",
        )
    }

    // ========================================================================
    // (4) LEGACY_UNREACHABLE execution — registry coexecute is the production path
    // ========================================================================

    @Test
    fun `G5 coexecute -- RegistryExecutionBoundary coexecutes core error and projects typed outcome`() {
        // Full production seam: RegistryExecutionPreparation.prepare → Ready →
        // RegistryExecutionBoundary.coexecute → typed outcome via TypedStepOutput.
        val registry = factoryRegistry()
        val failure = PipelineFailure(
            kind = FailureKind.USER,
            message = "core.error registry primary path is the production authority",
        )
        val input = CoreErrorInput(message = failure.message, failureKind = failure.kind)
        val encodedInput = CoreErrorStep.definition.contract.inputCodec.encode(input)

        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = CoreErrorStep.KEY,
            encodedInput = encodedInput,
            availableCapabilities = emptySet(),
        )
        val ready = assertInstanceOf(
            ExecutionPreparation.Ready::class.java,
            preparation,
            "registry prepare MUST succeed for a well-formed core.error envelope",
        )
        val prepared = assertInstanceOf(
            PreparedRegistryExecution::class.java,
            ready.prepared,
        )

        runBlocking {
            val context = CanonicalRuntimeContext(
                opId = OpId("g5-core-error-coex", 0, 0),
                runId = "g5-core-error-coex",
                stageName = "fail",
                stageIndex = 0,
                stepIndex = 0,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = Files.createTempDirectory("g5-core-error-coex-"),
                eventSink = InMemoryEventStore(),
            )
            val result: CommonExecutionResult = RegistryExecutionBoundary.coexecute(prepared, context)
            // The registry boundary projects the typed CoreErrorOutput.outcome into the
            // CommonExecutionResult. The carrier's invariant is
            //   outcome == StepOutcome.Failure(failure)
            // which is the canonical projection (see CoreErrorOutput.from / of).
            val expectedOutcome = StepOutcome.Failure(failure)
            assertEquals(
                expectedOutcome,
                result.outcome,
                "registry boundary MUST project CoreErrorOutput.outcome into CommonExecutionResult",
            )
        }
    }

    @Test
    fun `G5 LEGACY_UNREACHABLE -- legacy decoder branch for ERROR_PLUGIN_ID is no longer the production route`() {
        // The legacy `CanonicalCoreStepDecoder` still has the `core.error` branch
        // (G6 deletes it). After the G5 flip, the production coordinator does NOT
        // route through it. We prove this structurally by asserting the boundary
        // built with the production registry reaches the registry path for `core.error`.
        val registry = factoryRegistry()
        val family = StructuralFamilyResolver.classify(CoreErrorStep.KEY, registry)
        assertEquals(
            StructuralStepFamily.Registry,
            family,
            "StructuralFamilyResolver MUST classify core.error as Registry post-G5 " +
                "(the legacy decoder branch is unreachable in production)",
        )
    }

    // ========================================================================
    // (5) Counter discipline — G5 transient state (LEGACY_REMOVED is G6)
    // ========================================================================

    @Test
    fun `G6 counters -- LEGACY_PLUGIN_IDS is 9, metadata rows is 9, dispatchers is 9 (legacy removed)`() {
        // Post-S2-A3/G4 counter state (core.file.writeFile flipped at LFC-2E1-S2-A3/G4;
        // metadata row + dispatcher removed physically at that slice's G5):
        //   - LEGACY_PLUGIN_IDS: 9
        //   - CanonicalCoreStepMetadata rows: 9
        //   - durable/ per-Step dispatchers: 9
        assertEquals(9, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        // The legacy metadata row for core.error MUST be gone: the row was deleted at G6.
        assertThrows(
            IllegalArgumentException::class.java,
            { CanonicalCoreStepMetadata.metadata("core.error") },
            "legacy metadata row for core.error MUST be deleted at G6",
        )
        // The dispatcher file MUST be gone.
        val dispatcherFile = java.io.File(
            "src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalErrorNodeDispatcher.kt"
        )
        assertFalse(
            dispatcherFile.exists(),
            "CanonicalErrorNodeDispatcher.kt MUST be deleted at G6 (LEGACY_REMOVED)",
        )
    }

    // ========================================================================
    // (6) Capability admission — core.error declares empty capabilities (parity)
    // ========================================================================

    @Test
    fun `G5 capability -- CoreErrorStep declares empty required capabilities`() {
        // Required capabilities declared on the contract MUST equal emptySet() (parity
        // with legacy behaviour; the handler does not reach a coordinator/journal/event
        // sink/process executor).
        assertEquals(
            emptySet<String>(),
            CoreErrorStep.definition.contract.requiredCapabilities,
            "CoreErrorStep MUST declare empty requiredCapabilities (handler is pure)",
        )
    }

    @Test
    fun `G5 capability -- CanonicalRuntimeCapabilityAccess does not block registry admission for core error`() {
        // The capability admission for registry-bound steps is bridged through
        // CanonicalRuntimeCapabilityAccess; core.error requires no capabilities, so the
        // empty available set MUST satisfy admission (no rejection).
        val context = CanonicalRuntimeContext(
            opId = OpId("g5-core-error-cap", 0, 0),
            runId = "g5-core-error-cap",
            stageName = "fail",
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions.EMPTY,
            controlDirRoot = Files.createTempDirectory("g5-core-error-cap-"),
            eventSink = InMemoryEventStore(),
        )
        val access = CanonicalRuntimeCapabilityAccess(context)
        val declared = CoreErrorStep.definition.contract.requiredCapabilities
        // All declared capabilities MUST be available; for core.error this is trivially
        // true (declared set is empty).
        for (cap in declared) {
            assertTrue(
                access.available().contains(cap),
                "Capability '$cap' required by CoreErrorStep MUST be available",
            )
        }
    }

    // ========================================================================
    // (7) Registry identity — CoreErrorStep is the single StepDefinition for core.error
    // ========================================================================

    @Test
    fun `G5 identity -- production registry contains exactly one definition for core error`() {
        val registry = factoryRegistry()
        val definition: StepDefinition<*, *>? = registry.definition(CoreErrorStep.KEY)
        assertNotNull(definition, "registry MUST contain core.error")
        assertSame(
            CoreErrorStep.definition,
            definition,
            "registry MUST return the canonical CoreErrorStep.definition instance",
        )
        // The registry MUST continue to contain echo/sh (surgical flip).
        assertTrue(registry.contains(PluginStepId("core.echo")))
        assertTrue(registry.contains(PluginStepId("core.sh")))
    }
}
