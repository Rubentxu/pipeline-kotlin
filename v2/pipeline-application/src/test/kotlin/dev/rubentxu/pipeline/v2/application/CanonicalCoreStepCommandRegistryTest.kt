package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * UAT-LFC1-008-REGISTRY: Sealed hierarchy derives canonicalCoreStepIds.
 *
 * Verifies:
 * - sealedSubclasses has exactly 10 entries (WriteFile, EmitEvent, Milestone,
 *   DeleteDir, CleanWs, Load, Pwd, IsUnix, WaitUntil, ArchiveArtifacts).
 *   S3.1 removed Echo (core.echo migrated to the open StepRegistry via CoreEchoStep).
 *   S6 removed Shell (core.sh migrated to the open StepRegistry via CoreShellStep).
 *   LFC-2E1-S2-A1 / G6 removed Error (core.error migrated to the open StepRegistry
 *   via CoreErrorStep).
 *   LFC-2E1-S2-A2 / G5 removed Sleep (core.sleep migrated to the open StepRegistry
 *   via CoreSleepStep; CERTIFIED at S2-A2/G8).
 * - LEGACY_PLUGIN_IDS derived from the sealed hierarchy matches the expected set.
 * - Each subtype's pluginId and defaultMetadata match the expected values.
 *
 * This test enforces EC-9: adding a new step variant requires exactly
 * 3 edits across 2 files (variant + decoder when + dispatcher when).
 */
class CanonicalCoreStepCommandRegistryTest {

    @Test
    fun `sealedSubclasses has exactly 10 entries`() {
        val subclasses = CanonicalCoreStepCommand::class.sealedSubclasses
        assertEquals(10, subclasses.size, "Expected exactly 10 sealed subtypes. Found: ${subclasses.map { it.simpleName }}")
    }

    @Test
    fun `LEGACY_PLUGIN_IDS matches expected set`() {
        val expected = setOf(
            // core.sleep removed at LFC-2E1-S2-A2 / G5 (registry-routed, CERTIFIED).
            // core.file.writeFile removed at LFC-2E1-S2-A3 / G4 (registry-routed).
            "core.emit.event",
            "core.milestone",
            // P1a — workflow-control (v0.33.0)
            "core.deleteDir",
            "core.cleanWs",
            "core.load",
            // P1b — utility (v0.33.0)
            "core.pwd",
            "core.isUnix",
            "core.waitUntil",
            // P2 — archiveArtifacts (v0.33.1)
            "core.archiveArtifacts",
        )
        // Assert against the registry — single source of truth, no duplication
        assertEquals(expected, CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS, "LEGACY_PLUGIN_IDS must match expected set")
    }

    @Test
    fun `WriteFile has correct pluginId and defaultMetadata`() {
        val writeInstance = CanonicalCoreStepCommand.WriteFile("file.txt", "content", "utf-8")
        assertEquals("core.file.writeFile", writeInstance.pluginId)
        assertEquals(setOf(Effect.WRITES_WORKSPACE), writeInstance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, writeInstance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `EmitEvent has correct pluginId and defaultMetadata`() {
        val emitInstance = CanonicalCoreStepCommand.EmitEvent("CatchErrorTriggered", emptyMap())
        assertEquals("core.emit.event", emitInstance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), emitInstance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, emitInstance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `Milestone has correct pluginId and defaultMetadata`() {
        val milestoneInstance = CanonicalCoreStepCommand.Milestone(ordinal = 1, label = "post-error")
        assertEquals("core.milestone", milestoneInstance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), milestoneInstance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, milestoneInstance.defaultMetadata.replayPolicy)
    }

    // P1a — workflow-control canonical step families

    @Test
    fun `DeleteDir has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.DeleteDir(path = ".")
        assertEquals("core.deleteDir", instance.pluginId)
        assertEquals(setOf(Effect.WRITES_WORKSPACE), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `CleanWs has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.CleanWs(deleteDirs = true, patterns = emptyList())
        assertEquals("core.cleanWs", instance.pluginId)
        assertEquals(setOf(Effect.WRITES_WORKSPACE), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `Load has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.Load(path = "loaded.pipeline.kts")
        assertEquals("core.load", instance.pluginId)
        assertEquals(setOf(Effect.EXECUTES_SUBPROCESS), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }

    // P1b — utility canonical step families

    @Test
    fun `Pwd has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.Pwd(tmp = false)
        assertEquals("core.pwd", instance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `IsUnix has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.IsUnix()
        assertEquals("core.isUnix", instance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `WaitUntil has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.WaitUntil(initialRecurrencePeriod = 1000L, quiet = false)
        assertEquals("core.waitUntil", instance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }

    // P2 — archiveArtifacts canonical step family (v0.33.1)

    @Test
    fun `ArchiveArtifacts has correct pluginId and defaultMetadata`() {
        val instance = CanonicalCoreStepCommand.ArchiveArtifacts(
            artifacts = "build/**/*.jar",
            allowEmptyArchive = false,
            excludes = "",
            fingerprint = true,
        )
        assertEquals("core.archiveArtifacts", instance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), instance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, instance.defaultMetadata.replayPolicy)
    }
}
