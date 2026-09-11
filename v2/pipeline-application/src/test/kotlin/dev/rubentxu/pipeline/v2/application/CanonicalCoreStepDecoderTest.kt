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
    fun `decodes a versioned sleep node into its typed durable command`() {
        val node = OpaqueStepNode(
            id = StepId("build/sleep-0"),
            pluginStepId = PluginStepId("core.sleep"),
            payload = VersionedStepPayload(
                "dsl-v1",
                """{"kind":"sleep","seconds":3}""",
            ),
        )

        assertEquals(
            CanonicalCoreStepCommand.Sleep(3),
            CanonicalCoreStepDecoder.decode(node),
        )
    }

    @Test
    fun `decodes writeFile plugin id into WriteFile typed command`() {
        val node = OpaqueStepNode(
            id = StepId("build/writefile-0"),
            pluginStepId = PluginStepId("core.file.writeFile"),
            payload = VersionedStepPayload(
                "dsl-v1",
                """{"kind":"writeFile","file":"output.txt","text":"hello world","encoding":"UTF-8"}""",
            ),
        )

        assertEquals(
            CanonicalCoreStepCommand.WriteFile(
                file = "output.txt",
                text = "hello world",
                encoding = "UTF-8",
            ),
            CanonicalCoreStepDecoder.decode(node),
        )
    }

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
