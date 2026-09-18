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
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S2-A6 / G4 — REGISTRY_PRIMARY fitness for `core.pwd`.
 *
 * Proves the eight post-flip properties:
 *   1. core.pwd is registered in the production registry (G1 candidate alive through G4)
 *   2. core.pwd is absent from LEGACY_PLUGIN_IDS (the G4 flip itself)
 *   3. StructuralFamilyResolver classifies core.pwd as Registry
 *   4. core.pwd.tmp continues to be Registry (G3T invariant preserved across G4)
 *   5. Legacy routing cannot select core.pwd (no LegacyCore fallback)
 *   6. Registry execution produces typed PwdOutput via CorePwdStep
 *   7. WorkspaceIdentity + EventSink capability admission is preserved
 *   8. Registry descriptor metadata matches the legacy row (effect+replayPolicy) — proves
 *      the legacy metadata authority is dead code on the production path; the legacy
 *      source code (CanonicalPwdNodeDispatcher.kt, CanonicalCoreStepCommand.Pwd subtype,
 *      PWD_PLUGIN_ID decoder branch, CanonicalCoreStepMetadata["core.pwd"] row) remains
 *      physically present but unreachable through production routing until G5.
 */
@Timeout(30)
class CorePwdRegistryPrimaryFitnessTest {

    private val key = PluginStepId("core.pwd")

    private fun factoryRegistry() = CoreStepRegistryFactory.registry()

    @Test
    fun `G4 flip - core dot pwd is registered in the production registry`() {
        val registry = factoryRegistry()
        assertTrue(
            registry.contains(key),
            "G1 candidate MUST remain registered through the G4 flip",
        )
        assertSame(
            CorePwdStep.definition,
            registry.definition(key),
            "registry MUST resolve core.pwd to the canonical CorePwdStep.definition",
        )
    }

    @Disabled("Historical S2-A6/G4 snapshot: S2-A9/G5 (2026-09-13) physically removed core.milestone; the 6-key count is superseded by `G5 milestone - core dot pwd counter check` below. Preserved verbatim for traceability.")
    @Test
    fun `G4 flip - core dot pwd is absent from LEGACY_PLUGIN_IDS`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.pwd MUST be removed from LEGACY_PLUGIN_IDS; flip is the single point of structural change",
        )
        assertEquals(
            6,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G4 counter: LEGACY_PLUGIN_IDS converges 7 (post-S2-A5/G4) -> 6 (post-S2-A6/G4)",
        )
    }

    // S2-A9 / G5 (2026-09-13): core.milestone physically removed (LEGACY_REMOVED). The
    // historical S2-A6/G4 snapshot above is preserved verbatim for traceability. The
    // post-S2-A9/G5 counter for core.pwd (still registry-primary) converges to 4.
    @Disabled("Historical S2-A9/G5 snapshot: S2-A10/G4 (2026-09-13) REGISTRY-PRIMARY-flipped core.cleanWs; the 4-key count is superseded by `G4 flip - core dot pwd stays absent post-S2-A10-G4` below. Preserved verbatim for traceability.")
    @Test
    fun `G5 milestone - core dot pwd stays absent from LEGACY_PLUGIN_IDS post-S2-A9-G5`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.pwd MUST remain absent from LEGACY_PLUGIN_IDS post-S2-A9/G5 flip of core.milestone",
        )
        assertEquals(
            4,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G5 counter: LEGACY_PLUGIN_IDS converges 6 (post-S2-A6/G4) -> 4 (post-S2-A9/G5)",
        )
    }

    // S2-A10 / G4 (2026-09-13): core.cleanWs flipped to REGISTRY_PRIMARY; legacy decoder
    // branch / dispatcher file / metadata row remain physically present (UNREACHABLE in
    // production) until S2-A10 / G5 closes this lane. Counter converges 4/4/4 -> 3/4/4.
    @Disabled("Historical S2-A10/G4 snapshot: S2-B10/G5 (2026-09-13) physically removed core.archiveArtifacts; the 3-key count is superseded by `G5 LEGACY_REMOVED post-S2-B10-G5 - core dot pwd stays absent`. Preserved verbatim for traceability.")
    @Test
    fun `G4 flip - core dot pwd stays absent post-S2-A10-G4`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.pwd MUST remain absent from LEGACY_PLUGIN_IDS post-S2-A10/G4 flip of core.cleanWs",
        )
        assertEquals(
            3,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G4 counter: LEGACY_PLUGIN_IDS converges 4 (post-S2-A9/G5) -> 3 (post-S2-A10/G4)",
        )
    }

    // S2-A10 / G5 (2026-09-13): core.cleanWs legacy forms physically removed (LEGACY_REMOVED).
    // The historical S2-A10/G4 snapshot above is preserved verbatim for traceability. The
    // post-S2-A10/G5 counter converges to 3/3/3 (LEGACY_REMOVED closed).
    @Disabled("Historical S2-A10/G5 snapshot: S2-B10/G5 (2026-09-13) physically removed core.archiveArtifacts; the 3-key count is superseded by `G5 LEGACY_REMOVED post-S2-B10-G5 - core dot pwd stays absent`. Preserved verbatim for traceability.")
    @Test
    fun `G5 LEGACY_REMOVED - core dot pwd stays absent post-S2-A10-G5`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.pwd MUST remain absent from LEGACY_PLUGIN_IDS post-S2-A10/G5 LEGACY_REMOVED of core.cleanWs",
        )
        assertEquals(
            3,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G5 counter: LEGACY_PLUGIN_IDS converges 3 (post-S2-A10/G4) -> 3 (post-S2-A10/G5, LEGACY_REMOVED closed)",
        )
    }

    // S2-B10 / G5 (2026-09-13): core.archiveArtifacts legacy forms physically removed
    // (LEGACY_REMOVED). Counter converges 3/3/3 -> 2/2/2. Historical S2-A10/G4 and S2-A10/G5
    // snapshots above preserved verbatim for traceability.
    //
    // WU-LPR-301 / G5 (2026-09-18): core.load and core.waitUntil retired too. Counter
    // converges 2/2/2 -> 0/0/0. The pre-WU-LPR-301 snapshot above is preserved verbatim
    // for traceability; the active post-WU-LPR-301 / G5 property is the empty set.
    @Test
    fun `G5 LEGACY_REMOVED post-WU-LPR-301-G5 - core dot pwd stays absent (closed set)`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.pwd MUST remain absent from LEGACY_PLUGIN_IDS post-WU-LPR-301/G5 LEGACY_REMOVED",
        )
        assertEquals(
            0,
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size,
            "G5 counter: LEGACY_PLUGIN_IDS converges 2 (post-S2-B10/G5) -> 0 (post-WU-LPR-301/G5, " +
                "LEGACY_REMOVED closed for the last two keys)",
        )
        assertEquals(
            emptySet<String>(),
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "post-WU-LPR-301/G5: the legacy set is empty — burn-down closed",
        )
    }

    @Test
    fun `G4 flip - StructuralFamilyResolver classifies pwd as Registry with the production registry`() {
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(key, factoryRegistry()),
            "core.pwd MUST route as Registry family post-flip",
        )
    }

    @Test
    fun `G4 flip - core dot pwd dot tmp continues to resolve as Registry (G3T invariant preserved)`() {
        // G3T introduced core.pwd.tmp as a NEW registry entry; G4 MUST NOT regress it to
        // LegacyCore. The DSL `pwd(tmp=true)` and the registry entry stay on the open registry.
        val tmpKey = PluginStepId("core.pwd.tmp")
        assertTrue(
            factoryRegistry().contains(tmpKey),
            "G3T invariant: core.pwd.tmp MUST remain registered",
        )
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(tmpKey, factoryRegistry()),
            "core.pwd.tmp MUST continue to route as Registry family post-G4",
        )
    }

    @Test
    fun `G4 flip - legacy routing cannot select pwd (no LegacyCore fallback)`() {
        // Direct proof: with core.pwd absent from LEGACY_PLUGIN_IDS, the composite
        // metadata resolver MUST NOT fall back to CanonicalCoreStepMetadata. The legacy
        // row still answers (LEGACY_UNREACHABLE; type-loadable for parity tests), but
        // the production path is the registry descriptor — not the legacy row.
        val resolver = RegistryStepMetadataResolver.composite(factoryRegistry())
        val descriptor = CorePwdStep.definition.contract.descriptor
        // Production resolver returns the registry descriptor metadata (byte-equivalent).
        val productionMeta = resolver.resolve(key)
        assertNotNull(productionMeta, "RegistryStepMetadataResolver MUST resolve core.pwd via the registry descriptor")
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
    fun `G4 flip - registry execution produces typed PwdOutput via CorePwdStep`() {
        // Direct execution path: registry preparation + boundary coexecute MUST succeed
        // and produce the workspace path. The legacy dispatcher is NOT called.
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = factoryRegistry(),
                key = key,
                encodedInput = CorePwdStep.definition.contract.inputCodec.encode(PwdInput()),
                availableCapabilities = setOf(WORKSPACE_IDENTITY_CAPABILITY, EVENT_SINK_CAPABILITY),
            )
            val ready = preparation as ExecutionPreparation.Ready
            val workspace = java.nio.file.Files.createTempDirectory("g4-pwd")
            val ctx = CanonicalRuntimeContext(
                OpId("g4-pwd", 0, 0),
                "g4-pwd", "g4", 0, 0,
                ShOptions.EMPTY.copy(workspaceRoot = workspace),
                java.nio.file.Files.createTempDirectory("g4-pwd-ctrl"),
                InMemoryEventStore(),
            )
            val result = RegistryExecutionBoundary.coexecute(
                ready.prepared as PreparedRegistryExecution, ctx,
            )
            assertEquals(StepOutcome.Success, result.outcome)
            val output = CorePwdStep.definition.contract.outputCodec.decode(result.encodedOutput!!)
            assertNotNull(output, "registry MUST produce a typed PwdOutput")
            // The typed output MUST carry the workspace path that the context was built with.
            assertEquals(
                workspace.toAbsolutePath().toString(),
                output.path,
                "PwdOutput.path MUST match the canonical workspace root",
            )
        }
    }

    @Test
    fun `G4 flip - WorkspaceIdentity + EventSink capability admission is preserved`() {
        // The contract MUST still declare both capabilities; missing capability fails closed.
        val required = CorePwdStep.definition.contract.requiredCapabilities
        assertTrue(
            WORKSPACE_IDENTITY_CAPABILITY in required,
            "WorkspaceIdentity capability admission MUST remain declared",
        )
        assertTrue(
            EVENT_SINK_CAPABILITY in required,
            "EventSink capability admission MUST remain declared",
        )
        // No capability -> admission rejected (returns Rejected, handler never runs).
        runBlocking {
            val preparation = RegistryExecutionPreparation.prepare(
                registry = factoryRegistry(),
                key = key,
                encodedInput = CorePwdStep.definition.contract.inputCodec.encode(PwdInput()),
                availableCapabilities = emptySet(),
            )
            assertTrue(
                preparation is ExecutionPreparation.Rejected,
                "missing capabilities MUST fail closed at prepare-time (no handler execution)",
            )
            val rejected = preparation as ExecutionPreparation.Rejected
            // The Rejection message MUST identify the missing capabilities (any of them).
            assertTrue(
                rejected.reason.contains(WORKSPACE_IDENTITY_CAPABILITY.key) ||
                    rejected.reason.contains(EVENT_SINK_CAPABILITY.key),
                "Rejection MUST identify at least one missing capability",
            )
        }
    }

    @Disabled("Historical S2-A6/G4 snapshot: S2-A6/G5 (2026-09-12) removed the legacy metadata row from CanonicalCoreStepMetadata (LEGACY_REMOVED). This test compared the registry descriptor metadata to the legacy row — preserved verbatim for traceability; the byte-equivalence invariants are now asserted in S3PwdLegacyRemovedFitnessTest against the registry descriptor directly.")
    @Test
    fun `G4 flip - registry descriptor metadata matches legacy row (effects + replayPolicy)`() {
        // Defensive equivalence check: the registry descriptor MUST carry the same
        // metadata the legacy row claimed (READ_ONLY, MEMOIZED). This proves the legacy
        // row is dead code on the production path; the registry is the production
        // authority for both routing AND durable metadata.
        val descriptor = CorePwdStep.definition.contract.descriptor
        val legacyMeta = CanonicalCoreStepMetadata.metadata("core.pwd")
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
