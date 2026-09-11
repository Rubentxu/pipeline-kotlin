package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * S2-A4 / G4 — REGISTRY_PRIMARY + LEGACY_UNREACHABLE fitness for `core.emit.event`.
 *
 * Post-flip state proven here:
 *
 *  - registered=true; `core.emit.event` ∉ LEGACY_PLUGIN_IDS (G4 flip, surgical);
 *  - StructuralFamily == Registry with the production registry;
 *  - effective metadata source == the registry descriptor (READ_ONLY + MEMOIZED),
 *    byte-equivalent to the frozen legacy row, so fingerprints stay stable;
 *  - legacy forms PHYSICALLY REMOVED at G5 (S3EmitEventLegacyRemovedFitnessTest is the
 *    irreversible authority); this class keeps the pre-G5 flip assertions green;
 *  - capability declarations unchanged (EVENT_SINK + STAGE_IDENTITY);
 *  - the generic RegistryExecutionBoundary contains NO core.emit.event-specific
 *    branch (no privileged core path).
 *
 * Counter state frozen post-flip: LEGACY_PLUGIN_IDS = 8, metadata rows = 9,
 * per-Step dispatchers = 9 (emit.event legacy dispatcher still on disk until G5).
 */
@Timeout(30)
class CoreEmitEventRegistryPrimaryFitnessTest {

    private val key = PluginStepId("core.emit.event")
    private val decoderPath = java.nio.file.Paths.get(
        "src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
    private val nodeDispatcherPath = java.nio.file.Paths.get(
        "src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt")
    private val boundaryPath = java.nio.file.Paths.get(
        "src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt")

    private fun codeOnly(source: String): String =
        Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(
            Regex("//[^\\n]*").replace(source, ""),
            "",
        )

    private fun legacyIds(): Set<String> {
        val block = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)")
            .find(codeOnly(Files.readString(decoderPath)))?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found")
        return Regex("\\\"(core\\.[a-zA-Z.]+)\\\"").findAll(block).map { it.groupValues[1] }.toSet()
    }

    // ===== flip =====

    @Test
    fun `G4 flip — core dot emit dot event is NOT in LEGACY_PLUGIN_IDS and 8 residual keys remain`() {
        assertTrue(
            key.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "core.emit.event must be routed via the registry after the G4 flip",
        )
        assertEquals(8, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS.size)
        assertEquals(
            setOf(
                "core.milestone", "core.deleteDir", "core.cleanWs", "core.load",
                "core.pwd", "core.isUnix", "core.waitUntil", "core.archiveArtifacts",
            ),
            CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS,
            "the 8 residual legacy keys must be exactly the pre-authorized set",
        )
    }

    @Test
    fun `G4 flip — source-level LEGACY_PLUGIN_IDS no longer contains core emit event`() {
        assertFalse(
            "core.emit.event" in legacyIds(),
            "source-level snapshot must match the runtime set (no drift)",
        )
    }

    @Test
    fun `G4 flip — StructuralFamilyResolver classifies emit event as Registry with the production registry`() {
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(key, CoreStepRegistryFactory.registry()),
        )
    }

    @Test
    fun `G4 flip — production registry resolves emit event to the canonical CoreEmitEventStep definition`() {
        val registry = CoreStepRegistryFactory.registry()
        assertTrue(registry.contains(key))
        assertEquals(CoreEmitEventStep.KEY, registry.definition(key)?.contract?.key)
    }

    // ===== effective metadata authority =====

    @Test
    fun `G4 flip — descriptor metadata is byte-equivalent to the frozen legacy row`() {
        val descriptor = CoreEmitEventStep.definition.contract.descriptor
        assertEquals(setOf(Effect.READ_ONLY), descriptor.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, descriptor.replayPolicy)
    }

    @Test
    fun `G4 flip — composite metadata resolver reads the descriptor as effective authority`() {
        val resolver = RegistryStepMetadataResolver.composite(CoreStepRegistryFactory.registry())
        val metadata = resolver.resolve(key)
        assertNotNull(metadata, "production resolver MUST know core.emit.event post-flip")
        assertEquals(setOf(Effect.READ_ONLY), metadata!!.effects.toSet())
        assertEquals(ReplayPolicy.MEMOIZED, metadata.replayPolicy)
    }

    // ===== legacy physically present (LEGACY_UNREACHABLE, removal is G5) =====

    @Test
    fun `G5 — legacy forms physically removed, structural overlay protocol intentionally alive`() {
        assertFalse(
            java.nio.file.Files.exists(
                java.nio.file.Paths.get(
                    "src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalEmitEventNodeDispatcher.kt"),
            ),
            "legacy dispatcher file MUST be gone at G5 (authority: S3EmitEventLegacyRemovedFitnessTest)",
        )
        assertFalse(
            codeOnly(Files.readString(decoderPath)).contains("data class EmitEvent"),
            "legacy EmitEvent command MUST be gone at G5",
        )
        // Positive anti-over-removal check: the structural control protocol SURVIVES.
        assertTrue(
            Files.readString(
                java.nio.file.Paths.get(
                    "src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalInvocation.kt"),
            ).contains("core.emit.event"),
            "StructuralOverlayProjection MUST keep recognizing the raw core.emit.event envelope",
        )
    }

    // ===== capabilities unchanged =====

    @Test
    fun `G4 flip — contract still declares exactly EVENT_SINK plus STAGE_IDENTITY`() {
        assertEquals(
            setOf(EVENT_SINK_CAPABILITY, STAGE_IDENTITY_CAPABILITY),
            CoreEmitEventStep.definition.contract.requiredCapabilities,
        )
    }

    // ===== no privileged core path =====

    @Test
    fun `G4 — generic RegistryExecutionBoundary contains no core emit event specific branch`() {
        val boundary = codeOnly(Files.readString(boundaryPath))
        assertFalse(
            boundary.contains("core.emit.event") || boundary.contains("EmitEvent"),
            "the single execution seam MUST NOT branch on any concrete Step (no privileged core path)",
        )
    }

    // ===== structural overlay survives the flip (control context authority) =====

    @Test
    fun `G4 flip — StructuralOverlayProjection still projects catchError push pop from the raw envelope`() {
        val node = dev.rubentxu.pipeline.v2.domain.OpaqueStepNode(
            id = dev.rubentxu.pipeline.v2.domain.StepId("stage-0-step-0"),
            pluginStepId = key,
            payload = dev.rubentxu.pipeline.v2.domain.VersionedStepPayload(
                schemaVersion = "dsl-v1",
                encoded = """{"kind":"CatchErrorTriggered","emitted":"true"}""",
            ),
        )
        val ready = CanonicalStructuralPreparation.prepare(node) as StructuralPreparation.Ready
        val overlay = StructuralOverlayProjection.project(ready.invocation.stepKey, ready.envelope)
        assertTrue(
            overlay is StructuralOverlay.CatchErrorTriggered && overlay.emitted,
            "pre-decode control projection is independent of legacy execution and survives the flip",
        )
    }
}
