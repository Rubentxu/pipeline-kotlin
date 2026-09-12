package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S2-A8 / G4 — REGISTRY_PRIMARY fitness for `core.milestone`.
 *
 * Proves the eight post-flip properties (S2-A6 `CorePwdRegistryPrimaryFitnessTest`
 * precedent, applied to the milestone lane):
 *   1. core.milestone is registered in the production registry (G1 candidate alive through G4)
 *   2. core.milestone is absent from LEGACY_PLUGIN_IDS (the G4 flip itself); counters 4/5/5
 *   3. StructuralFamilyResolver classifies core.milestone as Registry
 *   4. Legacy routing cannot select core.milestone (no LegacyCore fallback)
 *   5. Registry execution produces typed MilestoneOutput via CoreMilestoneStep
 *   6. EventSink + MilestoneOperations capability admission is preserved (fail closed)
 *   7. Registry descriptor metadata matches the legacy row (effect+replayPolicy) — proves
 *      the legacy metadata authority is dead code on the production path; the legacy
 *      source code (CanonicalMilestoneNodeDispatcher.kt, CanonicalCoreStepCommand.Milestone
 *      subtype, Milestone decode branch, CanonicalCoreStepMetadata["core.milestone"] row)
 *      remains physically present but unreachable through production routing until G5.
 */
@Timeout(30)
class CoreMilestoneRegistryPrimaryFitnessTest {

    private val key = PluginStepId("core.milestone")

    private fun factoryRegistry() = CoreStepRegistryFactory.registry()

    @Test
    fun `G4 flip - core dot milestone is registered in the production registry`() {
        val registry = factoryRegistry()
        assertTrue(
            registry.contains(key),
            "G1 candidate MUST remain registered through the G4 flip",
        )
        assertSame(
            CoreMilestoneStep.definition,
            registry.definition(key),
            "registry MUST resolve core.milestone to the canonical CoreMilestoneStep.definition",
        )
    }

    @Test
    fun `G4 flip - core dot milestone is absent from LEGACY_PLUGIN_IDS (counters 4 5 5)`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.milestone MUST be removed from LEGACY_PLUGIN_IDS; flip is the single point of structural change",
        )
        assertEquals(
            4,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G4 counter: LEGACY_PLUGIN_IDS converges 5 (post-S2-A7/G5) -> 4 (post-S2-A8/G4)",
        )
        // Metadata rows and dispatcher files remain physically present until G5:
        // the key MUST still answer from the legacy metadata table (LEGACY_UNREACHABLE).
        assertNotNull(
            CanonicalCoreStepMetadata.metadata(key.value),
            "legacy metadata row MUST remain physically present at G4 (removed only at G5)",
        )
    }

    @Test
    fun `G4 flip - StructuralFamilyResolver classifies milestone as Registry with the production registry`() {
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(key, factoryRegistry()),
            "core.milestone MUST route as Registry family post-flip",
        )
    }

    @Test
    fun `G4 flip - legacy routing cannot select milestone (no LegacyCore fallback)`() {
        // Direct proof: with core.milestone absent from LEGACY_PLUGIN_IDS, the composite
        // metadata resolver MUST NOT fall back to CanonicalCoreStepMetadata. The legacy
        // row still answers (LEGACY_UNREACHABLE; type-loadable for parity tests), but
        // the production path is the registry descriptor — not the legacy row.
        val resolver = RegistryStepMetadataResolver.composite(factoryRegistry())
        val descriptor = CoreMilestoneStep.definition.contract.descriptor
        val productionMeta = resolver.resolve(key)
        assertNotNull(productionMeta, "RegistryStepMetadataResolver MUST resolve core.milestone via the registry descriptor")
        assertEquals(
            descriptor.effects.toSet(),
            productionMeta!!.effects,
            "production metadata MUST equal the registry descriptor effects",
        )
        assertEquals(
            descriptor.replayPolicy,
            productionMeta.replayPolicy,
            "production metadata MUST equal the registry descriptor replayPolicy",
        )
        // Resolution via an EMPTY registry fails closed — proves that the legacy row
        // is NOT consulted as a fallback by the production path.
        val emptyRegistry = dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry()
        val emptyComposite = RegistryStepMetadataResolver.composite(emptyRegistry)
        assertThrows(EngineInvariantViolation::class.java) {
            emptyComposite.resolve(key)
        }
    }

    @Test
    fun `G4 flip - registry execution produces typed MilestoneOutput via CoreMilestoneStep`() {
        // Direct execution path: registry preparation + boundary coexecute MUST succeed
        // and emit MilestoneReached. The legacy dispatcher is NOT called.
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = factoryRegistry(),
                key = key,
                encodedInput = CoreMilestoneStep.definition.contract.inputCodec.encode(
                    MilestoneInput(ordinal = 1, label = "g4-flip"),
                ),
                availableCapabilities = setOf(EVENT_SINK_CAPABILITY, MILESTONE_OPERATIONS_CAPABILITY),
            )
            val ready = preparation as ExecutionPreparation.Ready
            val eventStore = InMemoryEventStore()
            val ctx = CanonicalRuntimeContext(
                OpId("g4-milestone", 0, 0),
                "g4-milestone", "g4", 0, 0,
                ShOptions.EMPTY.copy(
                    workspaceRoot = java.nio.file.Files.createTempDirectory("g4-milestone"),
                ),
                java.nio.file.Files.createTempDirectory("g4-milestone-ctrl"),
                eventStore,
            )
            // The milestone lane needs the MILESTONE_OPERATIONS_CAPABILITY populated:
            // production wires it through RegistryExecutionBoundary.adapt(store).
            val boundary = RegistryExecutionBoundary.adapt(
                milestoneStateStore = dev.rubentxu.pipeline.v2.application.MilestoneStateStore(),
            )
            val result = boundary.execute(
                ready.prepared as PreparedRegistryExecution, ctx,
            )
            assertEquals(StepOutcome.Success, result.outcome)
            val output = CoreMilestoneStep.definition.contract.outputCodec.decode(result.encodedOutput!!)
            assertNotNull(output, "registry MUST produce a typed MilestoneOutput")
            assertEquals(1, output!!.ordinal)
            assertEquals("g4-flip", output.label)
            val events = eventStore.eventsFor("g4-milestone").toList()
            val reached = events.filterIsInstance<MilestoneReached>()
            assertEquals(1, reached.size, "exactly one MilestoneReached MUST be emitted through the registry path")
            assertEquals(1, reached.single().ordinal)
        }
    }

    @Test
    fun `G4 flip - EventSink + MilestoneOperations capability admission is preserved`() {
        // The contract MUST still declare both capabilities; missing capability fails closed.
        val required = CoreMilestoneStep.definition.contract.requiredCapabilities
        assertTrue(
            EVENT_SINK_CAPABILITY in required,
            "EventSink capability admission MUST remain declared",
        )
        assertTrue(
            MILESTONE_OPERATIONS_CAPABILITY in required,
            "MilestoneOperations capability admission MUST remain declared",
        )
        // No capability -> admission rejected (returns Rejected, handler never runs).
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = factoryRegistry(),
                key = key,
                encodedInput = CoreMilestoneStep.definition.contract.inputCodec.encode(
                    MilestoneInput(ordinal = 1, label = null),
                ),
                availableCapabilities = emptySet(),
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "missing capabilities MUST fail closed at prepare-time (no handler execution)",
            )
            val rejected = preparation as ExecutionPreparation.Rejected
            assertTrue(
                rejected.reason.contains(EVENT_SINK_CAPABILITY.key) ||
                    rejected.reason.contains(MILESTONE_OPERATIONS_CAPABILITY.key),
                "Rejection MUST identify at least one missing capability",
            )
        }
    }

    @Test
    fun `G4 flip - registry execution aborts non-increasing ordinal (sealed MilestoneAdvanceResult)`() {
        // Divergence/parity check on the same in-memory store the capability wires:
        // a second advance with ordinal <= previous MUST return the sealed
        // MilestoneAdvanceResult.Aborted case (no exceptions as control flow).
        val store = MilestoneStateStore()
        assertTrue(
            store.advance(1) is MilestoneAdvanceResult.Reached,
            "first advance MUST reach",
        )
        val second = store.advance(1)
        assertTrue(
            second is MilestoneAdvanceResult.Aborted,
            "non-increasing ordinal MUST abort (sealed MilestoneAdvanceResult.Aborted)",
        )
    }

    @Test
    fun `G4 flip - registry descriptor metadata matches legacy row (effects + replayPolicy)`() {
        // Defensive equivalence check: the registry descriptor MUST carry the same
        // metadata the legacy row claimed (READ_ONLY, MEMOIZED). This proves the legacy
        // row is dead code on the production path; the registry is the production
        // authority for both routing AND durable metadata.
        val descriptor = CoreMilestoneStep.definition.contract.descriptor
        val legacyMeta = CanonicalCoreStepMetadata.metadata("core.milestone")
        assertEquals(
            setOf(Effect.READ_ONLY),
            descriptor.effects.toSet(),
            "registry descriptor effects MUST equal the legacy metadata row",
        )
        assertEquals(
            ReplayPolicy.MEMOIZED,
            descriptor.replayPolicy,
            "registry descriptor replayPolicy MUST equal the legacy metadata row",
        )
        assertEquals(
            legacyMeta.effects,
            descriptor.effects.toSet(),
            "production metadata MUST byte-match the legacy row at G4 (until G5 removes the row)",
        )
        assertEquals(
            legacyMeta.replayPolicy,
            descriptor.replayPolicy,
            "production metadata MUST byte-match the legacy row at G4 (until G5 removes the row)",
        )
    }
}
