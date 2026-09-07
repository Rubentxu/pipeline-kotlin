package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * UAT-LFC1-008-REGISTRY: Sealed hierarchy derives canonicalCoreStepIds.
 *
 * Verifies:
 * - sealedSubclasses has exactly 7 entries (Shell, Echo, Error, Sleep, WriteFile, EmitEvent, Milestone).
 * - canonicalCoreStepIds derived from the sealed hierarchy matches the expected set.
 * - Each subtype's pluginId and defaultMetadata match the expected values.
 *
 * This test enforces EC-9: adding a new step variant requires exactly
 * 3 edits across 2 files (variant + decoder when + dispatcher when).
 */
class CanonicalCoreStepCommandRegistryTest {

    @Test
    fun `sealedSubclasses has exactly 7 entries`() {
        val subclasses = CanonicalCoreStepCommand::class.sealedSubclasses
        assertEquals(7, subclasses.size, "Expected exactly 7 sealed subtypes. Found: ${subclasses.map { it.simpleName }}")
    }

    @Test
    fun `ALL_PLUGIN_IDS matches expected set`() {
        val expected = setOf(
            "core.sh",
            "core.echo",
            "core.error",
            "core.sleep",
            "core.file.writeFile",
            "core.emit.event",
            "core.milestone",
        )
        // Assert against the registry — single source of truth, no duplication
        assertEquals(expected, CanonicalCoreStepCommand.ALL_PLUGIN_IDS, "ALL_PLUGIN_IDS must match expected set")
    }

    @Test
    fun `Shell has correct pluginId and defaultMetadata`() {
        val shellInstance = CanonicalCoreStepCommand.Shell("echo test", false, false)
        assertEquals("core.sh", shellInstance.pluginId)
        assertEquals(setOf(Effect.EXECUTES_SUBPROCESS), shellInstance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.RERUN, shellInstance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `Echo has correct pluginId and defaultMetadata`() {
        val echoInstance = CanonicalCoreStepCommand.Echo("hello")
        assertEquals("core.echo", echoInstance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), echoInstance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, echoInstance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `Error has correct pluginId and defaultMetadata`() {
        val errorInstance = CanonicalCoreStepCommand.Error(
            "failed",
            dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT
        )
        assertEquals("core.error", errorInstance.pluginId)
        assertEquals(setOf(Effect.ABORTS_PIPELINE), errorInstance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.NEVER, errorInstance.defaultMetadata.replayPolicy)
    }

    @Test
    fun `Sleep has correct pluginId and defaultMetadata`() {
        val sleepInstance = CanonicalCoreStepCommand.Sleep(5)
        assertEquals("core.sleep", sleepInstance.pluginId)
        assertEquals(setOf(Effect.READ_ONLY), sleepInstance.defaultMetadata.effects)
        assertEquals(ReplayPolicy.MEMOIZED, sleepInstance.defaultMetadata.replayPolicy)
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
}
