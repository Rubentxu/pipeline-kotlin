package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * B1.2c2-CDE.1: the structural pre-decode invocation is a lossless, step-agnostic projection of the
 * compiler node, so the durable payload (fingerprint/journal/operation-identity authority) is untouched
 * and the registry can later decode the same raw string. No decoded-command reconstruction.
 */
@Timeout(10)
class CanonicalInvocationTest {

    // Real dsl-v1 payload form the compiler emits for core.echo (DslCompiledPipelineCompiler.encodePayload).
    private val echoPayload = """{"kind":"echo","text":"hola structural"}"""
    private val echoNode = OpaqueStepNode(
        id = StepId("stage-0-step-0"),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload(schemaVersion = "dsl-v1", encoded = echoPayload),
    )

    @Test
    fun `fromNode projects the structural invocation before any decode`() {
        val invocation = CanonicalInvocation.fromNode(echoNode)
        assertEquals(PluginStepId("core.echo"), invocation.stepKey)
        assertEquals("dsl-v1", invocation.schemaVersion)
        // encoded input is exactly the persisted payload string, byte for byte.
        assertEquals(echoPayload, invocation.encodedInput.value)
    }

    @Test
    fun `structural projection preserves the durable payload representation`() {
        val invocation = CanonicalInvocation.fromNode(echoNode)
        // The durable identity the protocol canonicalizes over must be unchanged by the projection:
        // encodedInput is the same non-empty string as node.payload.encoded (no wrapping, no unwrapping).
        assertEquals(echoNode.payload.encoded, invocation.encodedInput.value)
        assertEquals(echoNode.payload.schemaVersion, invocation.schemaVersion)
    }
}
