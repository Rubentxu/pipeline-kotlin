package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json

/**
 * Round-trip tests for BlockStepNode IR variant.
 *
 * RED: BlockStepNode class referenced before declaration → unresolved-symbol.
 * GREEN: @Polymorphic on StepNode + BlockStepNode data class with @Serializable.
 */
class BlockStepNodeRoundTripTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @Test
    fun `BlockStepNode round-trips through JSON`() {
        val block = BlockStepNode(
            id = StepId("block-1"),
            pluginStepId = PluginStepId("core.catchError"),
            payload = VersionedStepPayload("dsl-v1", """{"buildResult":"FAILURE"}"""),
            body = listOf(
                OpaqueStepNode(
                    id = StepId("sh-1"),
                    pluginStepId = PluginStepId("core.sh"),
                    payload = VersionedStepPayload("dsl-v1", """{"command":"echo hello"}"""),
                ),
            ),
        )

        val encoded = json.encodeToString(BlockStepNode.serializer(), block)
        assertNotNull(encoded)
        assertTrue(encoded.contains("core.catchError"))
        assertTrue(encoded.contains("core.sh"))

        val decoded = json.decodeFromString(BlockStepNode.serializer(), encoded)
        assertEquals(block.id, decoded.id)
        assertEquals(block.pluginStepId, decoded.pluginStepId)
        assertEquals(block.body.size, decoded.body.size)
    }

    @Test
    fun `nested BlockStepNode round-trips through JSON`() {
        val innerBlock = BlockStepNode(
            id = StepId("inner-block"),
            pluginStepId = PluginStepId("core.dir"),
            payload = VersionedStepPayload("dsl-v1", """{"path":"build"}"""),
            body = listOf(
                OpaqueStepNode(
                    id = StepId("inner-sh"),
                    pluginStepId = PluginStepId("core.sh"),
                    payload = VersionedStepPayload("dsl-v1", """{"command":"make"}"""),
                ),
            ),
        )

        val outerBlock = BlockStepNode(
            id = StepId("outer-block"),
            pluginStepId = PluginStepId("core.catchError"),
            payload = VersionedStepPayload("dsl-v1", """{"buildResult":"FAILURE"}"""),
            body = listOf(
                OpaqueStepNode(
                    id = StepId("outer-sh"),
                    pluginStepId = PluginStepId("core.sh"),
                    payload = VersionedStepPayload("dsl-v1", """{"command":"echo outer"}"""),
                ),
                innerBlock,
            ),
        )

        val encoded = json.encodeToString(BlockStepNode.serializer(), outerBlock)
        val decoded = json.decodeFromString(BlockStepNode.serializer(), encoded)

        assertEquals(outerBlock.id, decoded.id)
        assertEquals(2, decoded.body.size)
        val nested = decoded.body[1] as BlockStepNode
        assertEquals(innerBlock.id, nested.id)
        assertEquals(1, nested.body.size)
    }

    @Test
    fun `OpaqueStepNode flat round-trip is byte-identical to baseline`() {
        // Regression guard: OpaqueStepNode must remain valid and unchanged
        val opaque = OpaqueStepNode(
            id = StepId("test-step"),
            pluginStepId = PluginStepId("core.shell"),
            payload = VersionedStepPayload("v1", """{"command":"echo test"}"""),
        )

        val encoded = json.encodeToString(OpaqueStepNode.serializer(), opaque)
        val decoded = json.decodeFromString(OpaqueStepNode.serializer(), encoded)

        assertEquals(opaque.id, decoded.id)
        assertEquals(opaque.pluginStepId, decoded.pluginStepId)
        assertEquals(opaque.payload, decoded.payload)
    }

    @Test
    fun `StepNode polymorphic round-trip selects correct variant`() {
        val block = BlockStepNode(
            id = StepId("poly-block"),
            pluginStepId = PluginStepId("core.catchError"),
            payload = VersionedStepPayload("dsl-v1", """{}"""),
            body = emptyList(),
        )

        val encoded = json.encodeToString(StepNode.serializer(), block)
        val decoded: StepNode = json.decodeFromString(StepNode.serializer(), encoded)

        assertTrue(decoded is BlockStepNode, "Polymorphic deserialization should select BlockStepNode variant")
        assertEquals(block.id, decoded.id)
    }
}
