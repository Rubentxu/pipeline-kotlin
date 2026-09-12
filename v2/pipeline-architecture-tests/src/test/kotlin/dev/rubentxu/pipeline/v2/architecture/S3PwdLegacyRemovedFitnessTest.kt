package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.application.CorePwdStep
import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralFamilyResolver
import dev.rubentxu.pipeline.v2.application.durable.StructuralStepFamily
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/**
 * S2-A6 / G5 — irreversible proof for `core.pwd`:
 *
 * ```
 * LEGACY_REMOVED(core.pwd) =
 *   membership absent (since G4)
 *   ∧ legacy command subtype absent
 *   ∧ legacy decoder branch absent
 *   ∧ legacy decoder constant absent
 *   ∧ legacy dispatcher absent (file + facade field + when-branch + context helper)
 *   ∧ legacy metadata row absent
 * ```
 *
 * AND the anti-over-removal half (the G5 law of this slice):
 *
 * ```
 * legacy execution removed  !=  PwdResolved emission removed
 * ```
 *
 * The PwdResolved event is the durable observation of the workspace identity read.
 * It MUST be emitted by the registry Step handler (CorePwdStep) — NOT by a legacy
 * dispatcher. Anti-over-removal: removing the legacy executor is correct, but
 * removing the PwdResolved emission would silently break scripted frontends
 * and downstream consumers that listen for the path identity.
 */
@Timeout(60)
class S3PwdLegacyRemovedFitnessTest {

    private val root = ScannerSupport.v2Root()
    private val decoder = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt")
    private val metadata = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt")
    private val nodeDispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt")
    private val pwdDispatcher = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalPwdNodeDispatcher.kt")
    private val pwdStep = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CorePwdStep.kt")

    /** The exact 6 residual legacy keys converged at G5 (after `core.pwd` removal). */
    private val residualIds = setOf(
        "core.milestone", "core.deleteDir", "core.cleanWs",
        "core.load", "core.waitUntil", "core.archiveArtifacts",
    )

    private fun read(path: java.nio.file.Path): String = Files.readString(path)
    private fun codeOnly(source: String): String =
        Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(
            Regex("//[^\\n]*").replace(source, ""),
            "",
        )

    private fun legacyIds(): Set<String> {
        val block = Regex("val LEGACY_PLUGIN_IDS: Set<String> = setOf\\(([\\s\\S]*?)\\)")
            .find(codeOnly(read(decoder)))?.value
            ?: error("LEGACY_PLUGIN_IDS declaration not found")
        return Regex("\"(core\\.[a-zA-Z.]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
    }

    // ===== registry authority intact =====

    @Test fun `core pwd remains registered and structurally registry owned`() {
        assertTrue(read(pwdStep).contains("PluginStepId(\"core.pwd\")"))
        val production = CoreStepRegistryFactory.registry()
        assertTrue(production.contains(PluginStepId("core.pwd")))
        assertEquals(
            StructuralStepFamily.Registry,
            StructuralFamilyResolver.classify(PluginStepId("core.pwd"), production),
        )
        assertFalse("core.pwd" in legacyIds())
    }

    // ===== irreversible removals =====

    @Test fun `legacy command subtype and decoder branch are absent`() {
        val decoderSource = codeOnly(read(decoder))
        assertFalse(decoderSource.contains("data class Pwd"))
        assertFalse(decoderSource.contains("PWD_PLUGIN_ID"))
    }

    @Test fun `legacy dispatcher is absent as file facade field branch and context helper`() {
        assertFalse(Files.exists(pwdDispatcher), "CanonicalPwdNodeDispatcher.kt MUST be deleted")
        val facade = codeOnly(read(nodeDispatcher))
        assertFalse(facade.contains("pwdDispatcher"))
        assertFalse(facade.contains("CanonicalCoreStepCommand.Pwd"))
        assertFalse(facade.contains("pwdContext"))
    }

    @Test fun `legacy metadata has no core pwd row`() {
        val source = codeOnly(read(metadata))
        assertFalse(Regex("\"core\\.pwd\"\\s+to\\s+StepMetadata\\(").containsMatchIn(source))
    }

    // ===== counter convergence 6 / 6 / 6 =====

    @Test fun `three residual legacy authorities converge to exact six step snapshots`() {
        assertEquals(residualIds, legacyIds())
        val metadataKeys = Regex("\"(core\\.[a-zA-Z.]+)\"\\s+to\\s+StepMetadata\\(")
            .findAll(codeOnly(read(metadata))).map { it.groupValues[1] }.toSet()
        assertEquals(residualIds, metadataKeys)
        val durable = root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable")
        val actualDispatchers = Files.list(durable).use { paths -> paths.map { it.fileName.toString() }
            .filter { it.startsWith("Canonical") && it.endsWith("NodeDispatcher.kt") && it != "CanonicalNodeDispatcher.kt" }
            .toList().toSet() }
        assertEquals(setOf(
            "CanonicalMilestoneNodeDispatcher.kt", "CanonicalDeleteDirNodeDispatcher.kt",
            "CanonicalCleanWsNodeDispatcher.kt", "CanonicalLoadNodeDispatcher.kt",
            "CanonicalWaitUntilNodeDispatcher.kt",
            "CanonicalArchiveArtifactsNodeDispatcher.kt",
        ), actualDispatchers)
        assertEquals(6, legacyIds().size)
        assertEquals(6, metadataKeys.size)
        assertEquals(6, actualDispatchers.size)
    }

    // ===== anti-over-removal: PwdResolved emission is ALIVE =====

    @Test fun `CorePwdStep handler emits the PwdResolved event after execution`() {
        // The PwdResolved event is the durable observation of the workspace identity read.
        // It MUST be emitted by the registry Step handler (CorePwdStep) — NOT by a legacy
        // dispatcher. Anti-over-removal: removing the legacy executor is correct, but
        // removing the PwdResolved emission would silently break scripted frontends
        // and downstream consumers that listen for the path identity.
        val source = read(pwdStep)
        assertTrue(
            source.contains("PwdResolved("),
            "CorePwdStep MUST keep emitting the PwdResolved event for downstream consumers",
        )
    }

    @Test fun `CorePwdStep handler keeps the WorkspaceIdentity + EventSink capability declaration`() {
        // The Step's requiredCapabilities must still include the WorkspaceIdentity
        // and EventSink capabilities, so that capability admission remains the
        // structural authority for granting workspace-root reads and event-sink
        // emission. The exact key strings are read from the live StepCapability
        // constants to stay in lockstep with the production declarations.
        val workspaceKey = dev.rubentxu.pipeline.v2.application.WORKSPACE_IDENTITY_CAPABILITY.key
        val eventSinkKey = dev.rubentxu.pipeline.v2.application.EVENT_SINK_CAPABILITY.key
        val requiredKeys = CorePwdStep.definition.contract.requiredCapabilities.map { it.key }.toSet()
        assertTrue(
            workspaceKey in requiredKeys,
            "CorePwdStep.descriptor MUST keep declaring the workspace-identity capability; required=$requiredKeys",
        )
        assertTrue(
            eventSinkKey in requiredKeys,
            "CorePwdStep.descriptor MUST keep declaring the event-sink capability; required=$requiredKeys",
        )
    }

    @Test fun `production metadata resolver serves core pwd from the descriptor`() {
        val resolver = RegistryStepMetadataResolver.composite(CoreStepRegistryFactory.registry())
        val metadataRow = resolver.resolve(PluginStepId("core.pwd"))
        assertTrue(metadataRow != null, "registry descriptor is the effective metadata authority post-G5")
        // Byte-equivalence with the legacy row invariants that the historical
        // CanonicalCoreStepCommand.Pwd.defaultMetadata used to declare.
        assertEquals(setOf(Effect.READ_ONLY), metadataRow!!.effects)
        assertEquals(ReplayPolicy.MEMOIZED, metadataRow.replayPolicy)
    }
}
