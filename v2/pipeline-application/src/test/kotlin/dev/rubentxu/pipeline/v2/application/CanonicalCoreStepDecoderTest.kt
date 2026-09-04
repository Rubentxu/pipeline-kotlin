package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalCoreStepDecoderTest {
    @Test
    fun `decodes a versioned shell node into its typed durable command`() {
        val node = OpaqueStepNode(
            id = StepId("build/sh-0"),
            pluginStepId = PluginStepId("core.sh"),
            payload = VersionedStepPayload(
                "dsl-v1",
                """{"kind":"sh","command":"make test","isScriptBlock":true,"returnStdout":false}""",
            ),
        )

        assertEquals(
            CanonicalCoreStepCommand.Shell(
                command = "make test",
                isScriptBlock = true,
                returnStdout = false,
            ),
            CanonicalCoreStepDecoder.decode(node),
        )
    }

    @Test
    fun `decodes a versioned echo node into its typed durable command`() {
        val node = OpaqueStepNode(
            id = StepId("build/echo-0"),
            pluginStepId = PluginStepId("core.echo"),
            payload = VersionedStepPayload(
                "dsl-v1",
                """{"kind":"echo","text":"hello canonical runtime"}""",
            ),
        )

        assertEquals(
            CanonicalCoreStepCommand.Echo("hello canonical runtime"),
            CanonicalCoreStepDecoder.decode(node),
        )
    }

    @Test
    fun `decodes a versioned error node with its typed failure kind`() {
        val node = OpaqueStepNode(
            id = StepId("build/error-0"),
            pluginStepId = PluginStepId("core.error"),
            payload = VersionedStepPayload(
                "dsl-v1",
                """{"kind":"error","message":"deployment denied","failureKind":"USER"}""",
            ),
        )

        assertEquals(
            CanonicalCoreStepCommand.Error("deployment denied", FailureKind.USER),
            CanonicalCoreStepDecoder.decode(node),
        )
    }

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
}
