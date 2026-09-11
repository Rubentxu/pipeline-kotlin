package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CanonicalCoreStepDecoderTest {
    @Test
    fun `core sleep is no longer legacy decodable (registry-routed since S2-A2 G5)`() {
        val node = OpaqueStepNode(
            id = StepId("build/sleep-0"),
            pluginStepId = PluginStepId("core.sleep"),
            payload = VersionedStepPayload(
                "dsl-v1",
                """{"kind":"sleep","seconds":3}""",
            ),
        )

        // Fail-closed: unknown legacy plugin ids MUST be rejected, never re-routed.
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalCoreStepDecoder.decode(node)
        }
    }

    // core.file.writeFile decoder branch removed at LFC-2E1-S2-A3/G5 (LEGACY_REMOVED);
    // writeFile now decodes via CoreWriteFileStep codec in the registry path.

    @Test
    fun `decodes emitEvent plugin id into EmitEvent typed command`() {
        val node = OpaqueStepNode(
            id = StepId("build/emit-0"),
            pluginStepId = PluginStepId("core.emit.event"),
            payload = VersionedStepPayload(
                "dsl-v1",
                """{"kind":"CatchErrorTriggered","buildResult":"FAILURE","stageResult":"FAILURE","message":"tolerated"}""",
            ),
        )

        val result = CanonicalCoreStepDecoder.decode(node)
        assertEquals("CatchErrorTriggered", (result as CanonicalCoreStepCommand.EmitEvent).kind)
        assertEquals("FAILURE", result.payload["buildResult"])
        assertEquals("FAILURE", result.payload["stageResult"])
        assertEquals("tolerated", result.payload["message"])
    }

    @Test
    fun `throws IllegalArgumentException for unknown plugin step id`() {
        val node = OpaqueStepNode(
            id = StepId("build/unknown-0"),
            pluginStepId = PluginStepId("core.unknown"),
            payload = VersionedStepPayload(
                "dsl-v1",
                """{"kind":"unknown"}""",
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalCoreStepDecoder.decode(node)
        }
    }
}
