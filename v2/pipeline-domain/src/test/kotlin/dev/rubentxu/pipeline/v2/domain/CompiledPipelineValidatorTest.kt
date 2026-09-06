package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for CompiledPipelineValidator recursion (EM-4).
 */
class CompiledPipelineValidatorTest {

    private fun makeBlockStepNode(
        id: String,
        pluginStepId: String,
        body: List<StepNode> = emptyList(),
    ): BlockStepNode = BlockStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId(pluginStepId),
        payload = VersionedStepPayload("dsl-v1", "{}"),
        body = body,
    )

    private fun makeOpaqueStepNode(id: String, pluginStepId: String): OpaqueStepNode = OpaqueStepNode(
        id = StepId(id),
        pluginStepId = PluginStepId(pluginStepId),
        payload = VersionedStepPayload("dsl-v1", """{"command":"echo test"}"""),
    )

    private fun makePipeline(vararg steps: StepNode): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("test-pipeline"),
        source = SourceDescriptor("test.kts", Digest("abc123")),
        stages = listOf(
            StageNode(
                id = StageId("test-stage"),
                name = "Test Stage",
                body = StageBody.Steps(steps.toList()),
            )
        ),
        pluginLockDigest = Digest("lock"),
    )

    @Test
    fun `depth 4 nesting throws BlockNestingDepthExceededException`() {
        // 4-deep nesting: depth 4 should fail
        val depth4 = makeBlockStepNode(
            id = "block-4",
            pluginStepId = "core.catchError",
            body = listOf(
                makeBlockStepNode(
                    id = "block-3",
                    pluginStepId = "core.dir",
                    body = listOf(
                        makeBlockStepNode(
                            id = "block-2",
                            pluginStepId = "core.dir",
                            body = listOf(
                                makeBlockStepNode(
                                    id = "block-1",
                                    pluginStepId = "core.dir",
                                    body = listOf(
                                        makeOpaqueStepNode("sh-1", "core.sh")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val pipeline = makePipeline(depth4)

        val exception = assertThrows(BlockNestingExceededException::class.java) {
            CompiledPipelineValidator.validate(pipeline)
        }

        assertEquals(4, exception.depth)
        assertEquals(3, exception.maxDepth)
    }

    @Test
    fun `BlockStepNode with takesBody false throws`() {
        // core.emit.event has takesBody=false in registry
        val invalidBlock = makeBlockStepNode(
            id = "invalid-block",
            pluginStepId = "core.emit.event",
            body = listOf(makeOpaqueStepNode("sh-1", "core.sh")),
        )

        val pipeline = makePipeline(invalidBlock)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            CompiledPipelineValidator.validate(pipeline)
        }

        assertTrue(exception.message!!.contains("takesBody=false"))
    }

    @Test
    fun `duplicate StepId within block throws`() {
        val block = makeBlockStepNode(
            id = "outer-block",
            pluginStepId = "core.catchError",
            body = listOf(
                makeOpaqueStepNode("sh-duplicate", "core.sh"),
                makeOpaqueStepNode("sh-duplicate", "core.sh"), // Same id!
            )
        )

        val pipeline = makePipeline(block)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            CompiledPipelineValidator.validate(pipeline)
        }

        assertTrue(exception.message!!.contains("Duplicate step id"))
        assertTrue(exception.message!!.contains("sh-duplicate"))
    }

    @Test
    fun `same StepId in different blocks is allowed`() {
        // Cross-block id collisions are allowed
        val block1 = makeBlockStepNode(
            id = "block-1",
            pluginStepId = "core.catchError",
            body = listOf(makeOpaqueStepNode("sh-1", "core.sh"))
        )
        val block2 = makeBlockStepNode(
            id = "block-2",
            pluginStepId = "core.dir",
            body = listOf(makeOpaqueStepNode("sh-1", "core.sh")) // Same id, different block
        )

        val pipeline = makePipeline(block1, block2)

        // Should not throw
        CompiledPipelineValidator.validate(pipeline)
    }

    @Test
    fun `depth 3 nesting is allowed`() {
        // Exactly depth 3 should pass
        val depth3 = makeBlockStepNode(
            id = "block-3",
            pluginStepId = "core.catchError",
            body = listOf(
                makeBlockStepNode(
                    id = "block-2",
                    pluginStepId = "core.dir",
                    body = listOf(
                        makeBlockStepNode(
                            id = "block-1",
                            pluginStepId = "core.dir",
                            body = listOf(makeOpaqueStepNode("sh-1", "core.sh"))
                        )
                    )
                )
            )
        )

        val pipeline = makePipeline(depth3)

        // Should not throw
        CompiledPipelineValidator.validate(pipeline)
    }

    @Test
    fun `valid pipeline with no blocks passes validation`() {
        val pipeline = makePipeline(
            makeOpaqueStepNode("sh-1", "core.sh"),
            makeOpaqueStepNode("echo-1", "core.echo"),
        )

        // Should not throw
        CompiledPipelineValidator.validate(pipeline)
    }

    @Test
    fun `valid nested block with children passes validation`() {
        val block = makeBlockStepNode(
            id = "catch-block",
            pluginStepId = "core.catchError",
            body = listOf(
                makeOpaqueStepNode("sh-1", "core.sh"),
                makeOpaqueStepNode("sh-2", "core.sh"),
                makeBlockStepNode(
                    id = "inner-dir",
                    pluginStepId = "core.dir",
                    body = listOf(makeOpaqueStepNode("sh-3", "core.sh"))
                )
            )
        )

        val pipeline = makePipeline(block)

        // Should not throw
        CompiledPipelineValidator.validate(pipeline)
    }
}
