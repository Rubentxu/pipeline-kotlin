package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.application.CoreEmitEventStep
import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * S2-A4 / G5 — irreversible proof for `core.emit.event`:
 *
 * ```
 * LEGACY_REMOVED(core.emit.event) =
 *   membership absent (since G4)
 *   ∧ legacy command subtype absent
 *   ∧ legacy decoder branch absent
 *   ∧ legacy dispatcher absent (file + facade field + when-branch + context helper)
 *   ∧ legacy metadata row absent
 * ```
 *
 * AND the anti-over-removal half (the G5 law of this slice):
 *
 * ```
 * legacy execution removed  !=  structural control protocol removed
 * ```
 *
 * The RAW `core.emit.event` envelope remains the catchError control protocol:
 * `StructuralOverlayProjection` must keep recognizing CatchErrorEntered / CatchErrorTriggered
 * pre-decode, `CanonicalStructuralPreparation` must still prepare such nodes, and the compiler
 * must keep emitting them. catchError depends on the envelope, NOT on the legacy executor.
 */
@Timeout(60)
class S3EmitEventLegacyRemovedFitnessTest {

    private val root = ScannerSupport.v2Root()
    private val decoder = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
    private val metadata = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt")
    private val nodeDispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt")
    private val emitEventDispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalEmitEventNodeDispatcher.kt")
    private val invocation = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalInvocation.kt")
    private val emitEventStep = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreEmitEventStep.kt")
    private val registryFactory = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt")


    private fun read(path: java.nio.file.Path): String = Files.readString(path)
    private fun codeOnly(source: String): String =
        Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(
            Regex("//[^\\n]*").replace(source, ""),
            "",
        )


    // ===== registry authority intact =====

    @Test fun `core emit event remains registered and structurally registry owned`() {
        assertTrue(read(registryFactory).contains("CoreEmitEventStep.registerInto(this)"))
        assertTrue(read(emitEventStep).contains("PluginStepId(\"core.emit.event\")"))
        val production = CoreStepRegistryFactory.registry()
        assertTrue(production.contains(PluginStepId("core.emit.event")))
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(PluginStepId("core.emit.event"), production),
        )
        assertFalse("core.emit.event" in LegacyResidualSnapshot.liveLegacyIds(root))
    }

    // ===== irreversible removals =====

    @Test fun `legacy command subtype and decoder branch are absent`() {
        val decoderSource = codeOnly(read(decoder))
        assertFalse(decoderSource.contains("data class EmitEvent"))
        assertFalse(decoderSource.contains("EMIT_EVENT_PLUGIN_ID"))
    }

    @Test fun `legacy dispatcher is absent as file facade field branch and context helper`() {
        assertFalse(Files.exists(emitEventDispatcher), "CanonicalEmitEventNodeDispatcher.kt MUST be deleted")
        val facade = codeOnly(read(nodeDispatcher))
        assertFalse(facade.contains("emitEventDispatcher"))
        assertFalse(facade.contains("CanonicalCoreStepCommand.EmitEvent"))
        assertFalse(facade.contains("emitEventContext"))
    }

    @Test fun `legacy metadata has no core emit event row`() {
        val source = codeOnly(read(metadata))
        assertFalse(Regex("\\\"core\\.emit\\.event\\\"\\s+to\\s+StepMetadata\\(").containsMatchIn(source))
    }

    // ===== counter convergence 6 / 6 / 6 (post S2-A6/G5 `core.pwd` removal) =====

    @Test fun `three residual legacy authorities converge to exact six step snapshots`() {
        // Single shared authority: ONE place to flip 6 -> 5 at the next G4/G5.
        LegacyResidualSnapshot.assertConverged(root)
    }

    // ===== anti-over-removal: the structural protocol is ALIVE =====

    @Test fun `structural overlay projection still recognizes the raw envelope control kinds`() {
        val source = read(invocation)
        assertTrue(
            source.contains("core.emit.event") &&
                source.contains("\"CatchErrorEntered\"") &&
                source.contains("\"CatchErrorTriggered\"") &&
                source.contains("EMIT_EVENT_PLUGIN"),
            "StructuralOverlayProjection MUST keep projecting catchError push/pop from the raw envelope",
        )
    }

    @Test fun `whitelist authority survives in the registry step`() {
        val source = read(emitEventStep)
        for (kind in setOf("CatchErrorEntered", "CatchErrorTriggered", "StageMarkedUnstable", "FileWritten")) {
            assertTrue(source.contains("\"$kind\""), "Step whitelist MUST keep '$kind'")
        }
    }

    @Test fun `production metadata resolver serves core emit event from the descriptor`() {
        val resolver = RegistryStepMetadataResolver.composite(CoreStepRegistryFactory.registry())
        val metadataRow = resolver.resolve(PluginStepId("core.emit.event"))
        assertTrue(metadataRow != null, "registry descriptor is the effective metadata authority post-G5")
    }
}
