package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.application.CoreIsUnixStep
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
 * S2-A5 / G5 — irreversible proof for `core.isUnix`:
 *
 * ```
 * LEGACY_REMOVED(core.isUnix) =
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
 * legacy execution removed  !=  platform-identity capability removed
 * ```
 *
 * The PlatformIdentity capability (CanonicalRuntimeCapabilityAccess) must remain the
 * single authority over `System.getProperty("os.name")` reads; only the legacy
 * executor that branched on a CanonicalCoreStepCommand.IsUnix data class is gone.
 * UnixDetected events keep flowing through the structural overlay protocol
 * pre-decode so the scripted frontends still see the runtime signal.
 */
@Timeout(60)
class S3IsUnixLegacyRemovedFitnessTest {

    private val root = ScannerSupport.v2Root()
    private val decoder = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
    private val metadata = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt")
    private val nodeDispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt")
    private val isUnixDispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalIsUnixNodeDispatcher.kt")
    private val isUnixStep = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreIsUnixStep.kt")
    private val platformAccess = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalRuntimeCapabilityAccess.kt")
    private val registryFactory = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt")


    private fun read(path: java.nio.file.Path): String = Files.readString(path)
    private fun codeOnly(source: String): String =
        Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(
            Regex("//[^\\n]*").replace(source, ""),
            "",
        )


    // ===== registry authority intact =====

    @Test fun `core isUnix remains registered and structurally registry owned`() {
        assertTrue(read(registryFactory).contains("CoreIsUnixStep.registerInto(this)"))
        assertTrue(read(isUnixStep).contains("PluginStepId(\"core.isUnix\")"))
        val production = CoreStepRegistryFactory.registry()
        assertTrue(production.contains(PluginStepId("core.isUnix")))
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(PluginStepId("core.isUnix"), production),
        )
        assertFalse("core.isUnix" in LegacyResidualSnapshot.liveLegacyIds(root))
    }

    // ===== irreversible removals =====

    @Test fun `legacy command subtype and decoder branch are absent`() {
        val decoderSource = codeOnly(read(decoder))
        assertFalse(decoderSource.contains("data class IsUnix"))
        assertFalse(decoderSource.contains("IS_UNIX_PLUGIN_ID"))
    }

    @Test fun `legacy dispatcher is absent as file facade field branch and context helper`() {
        assertFalse(Files.exists(isUnixDispatcher), "CanonicalIsUnixNodeDispatcher.kt MUST be deleted")
        val facade = codeOnly(read(nodeDispatcher))
        assertFalse(facade.contains("isUnixDispatcher"))
        assertFalse(facade.contains("CanonicalCoreStepCommand.IsUnix"))
        assertFalse(facade.contains("isUnixContext"))
    }

    @Test fun `legacy metadata has no core isUnix row`() {
        val source = codeOnly(read(metadata))
        assertFalse(Regex("\"core\\.isUnix\"\\s+to\\s+StepMetadata\\(").containsMatchIn(source))
    }

    // ===== counter convergence 6 / 6 / 6 (post S2-A6/G5 `core.pwd` removal) =====

    @Test fun `three residual legacy authorities converge to exact six step snapshots`() {
        // Single shared authority: ONE place to flip 6 -> 5 at the next G4/G5.
        // TRANSITIONAL (deleteDir G4 window): restore assertConverged at deleteDir G5.
        LegacyResidualSnapshot.assertCurrentState(root)
    }

    // ===== anti-over-removal: the structural protocol is ALIVE =====

    @Test fun `CoreIsUnixStep handler emits the UnixDetected event after execution`() {
        // The UnixDetected event is the durable observation of the platform identity read.
        // It MUST be emitted by the registry Step handler (CoreIsUnixStep) — NOT by a legacy
        // dispatcher. Anti-over-removal: removing the legacy executor is correct, but
        // removing the UnixDetected emission would silently break scripted frontends
        // (R4B ScriptedIsUnixRuntimeTest) that consume this event.
        val source = read(isUnixStep)
        assertTrue(
            source.contains("UnixDetected("),
            "CoreIsUnixStep MUST keep emitting the UnixDetected event for downstream consumers",
        )
    }

    @Test fun `PlatformIdentity capability remains the single os-name read authority`() {
        // Anti-over-removal: only the LEGACY EXECUTOR was removed. The capability surface
        // (CanonicalRuntimeCapabilityAccess with PLATFORM_IDENTITY_CAPABILITY + PlatformIdentity
        // implementation) is still alive and owns the single `System.getProperty("os.name")` read.
        // Removing it would be over-removal.
        val source = read(platformAccess)
        assertTrue(
            source.contains("PLATFORM_IDENTITY_CAPABILITY"),
            "CanonicalRuntimeCapabilityAccess MUST keep the platform-identity capability surface",
        )
    }

    @Test fun `CoreIsUnixStep keeps the PlatformIdentity capability declaration`() {
        // The Step's requiredCapabilities must still include the platform-identity
        // capability (key = "runtime.platform-identity"), so that capability admission
        // remains the structural authority for granting os-name reads.
        val requiredKeys = CoreIsUnixStep.definition.contract.requiredCapabilities.map { it.key }.toSet()
        assertTrue(
            "runtime.platform-identity" in requiredKeys,
            "CoreIsUnixStep.descriptor MUST keep declaring the platform-identity capability; required=$requiredKeys",
        )
    }

    @Test fun `production metadata resolver serves core isUnix from the descriptor`() {
        val resolver = RegistryStepMetadataResolver.composite(CoreStepRegistryFactory.registry())
        val metadataRow = resolver.resolve(PluginStepId("core.isUnix"))
        assertTrue(metadataRow != null, "registry descriptor is the effective metadata authority post-G5")
    }
}
