package dev.rubentxu.pipeline.v2.domain

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * WU-LPR-020 — Structural proof that the existing closed IR (`BlockStepNode`,
 * `StageBody`, `StageNode`) can carry body shapes `None`, `Single`, and `Named`
 * without introducing any plugin-specific node.
 *
 * The three body shapes:
 *
 *  - **None**: body is empty (0 children). The block exists but holds no steps.
 *  - **Single**: body holds exactly one child (singleton).
 *  - **Named**: body holds multiple children, each named via `StageNode.name`.
 *    Used by `parallel { branch("name") { ... } }` and any multi-step block.
 *
 * WU-LPR-020 constraint: tests only, NO engine migration. The proof is
 * structural — if a shape round-trips through JSON and is accepted by the
 * existing planner, the IR already carries it. The migration to a
 * BodyExecutionEngine happens later (WU-LPR-021..024).
 *
 * Authority for the shapes:
 *
 *  - `BlockStepNode.body: List<StepNode>` (CompiledPipeline.kt L141).
 *  - `StageBody.Steps(steps: List<StepNode>)` (CompiledPipeline.kt L112).
 *  - `StageBody.Parallel(branches: List<StageNode>)` (CompiledPipeline.kt L116).
 *  - `StageNode.name: String` (CompiledPipeline.kt L97).
 */
class WULpr020GenericBodyCarrierTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @Nested
    @DisplayName("BlockStepNode body shape coverage")
    inner class BlockStepNodeShapes {

        @Test
        fun `BlockStepNode with empty body represents None`() {
            val none = BlockStepNode(
                id = StepId("empty-block"),
                pluginStepId = PluginStepId("core.dir"),
                payload = VersionedStepPayload("dsl-v1", """{"path":"build"}"""),
                body = emptyList(),
            )
            assertTrue(none.body.isEmpty(), "None body MUST be empty list, not nullable")

            val encoded = json.encodeToString(BlockStepNode.serializer(), none)
            val decoded = json.decodeFromString(BlockStepNode.serializer(), encoded)
            assertEquals(0, decoded.body.size, "None body MUST round-trip as 0 children")
        }

        @Test
        fun `BlockStepNode with singleton body represents Single`() {
            val single = BlockStepNode(
                id = StepId("single-block"),
                pluginStepId = PluginStepId("core.sh"),
                payload = VersionedStepPayload("dsl-v1", """{"command":"echo one"}"""),
                body = listOf(
                    OpaqueStepNode(
                        id = StepId("only-step"),
                        pluginStepId = PluginStepId("core.echo"),
                        payload = VersionedStepPayload("dsl-v1", """{"text":"one"}"""),
                    ),
                ),
            )
            assertEquals(1, single.body.size, "Single body MUST be singleton")

            val encoded = json.encodeToString(BlockStepNode.serializer(), single)
            val decoded = json.decodeFromString(BlockStepNode.serializer(), encoded)
            assertEquals(1, decoded.body.size, "Single body MUST round-trip with exactly 1 child")
            assertEquals(StepId("only-step"), decoded.body[0].id)
        }

        @Test
        fun `BlockStepNode with multi-step body represents Named (via StepNode id)`() {
            val named = BlockStepNode(
                id = StepId("multi-block"),
                pluginStepId = PluginStepId("core.stage"),
                payload = VersionedStepPayload("dsl-v1", """{"stages":[]}"""),
                body = listOf(
                    OpaqueStepNode(id = StepId("first"), pluginStepId = PluginStepId("core.echo"),
                        payload = VersionedStepPayload("dsl-v1", """{"text":"a"}""")),
                    OpaqueStepNode(id = StepId("second"), pluginStepId = PluginStepId("core.echo"),
                        payload = VersionedStepPayload("dsl-v1", """{"text":"b"}""")),
                    OpaqueStepNode(id = StepId("third"), pluginStepId = PluginStepId("core.echo"),
                        payload = VersionedStepPayload("dsl-v1", """{"text":"c"}""")),
                ),
            )
            assertEquals(3, named.body.size)

            val encoded = json.encodeToString(BlockStepNode.serializer(), named)
            val decoded = json.decodeFromString(BlockStepNode.serializer(), encoded)
            assertEquals(3, decoded.body.size)
            assertEquals(listOf("first", "second", "third"), decoded.body.map { it.id.value })
        }
    }

    @Nested
    @DisplayName("StageBody Parallel branches coverage (Named branches)")
    inner class StageBodyParallelBranches {

        @Test
        fun `Parallel body with empty branches is None`() {
            val none = StageBody.Parallel(branches = emptyList())
            assertTrue(none.branches.isEmpty())

            val encoded = json.encodeToString(StageBody.Parallel.serializer(), none)
            val decoded = json.decodeFromString(StageBody.Parallel.serializer(), encoded)
            assertEquals(0, decoded.branches.size)
        }

        @Test
        fun `Parallel body with single named branch is Single+Named`() {
            val singleNamed = StageBody.Parallel(
                branches = listOf(
                    StageNode(
                        id = StageId("only-branch"),
                        name = "only-branch",
                        body = StageBody.Steps(steps = emptyList()),
                    ),
                ),
            )
            assertEquals(1, singleNamed.branches.size)
            assertEquals("only-branch", singleNamed.branches[0].name)

            val encoded = json.encodeToString(StageBody.Parallel.serializer(), singleNamed)
            val decoded = json.decodeFromString(StageBody.Parallel.serializer(), encoded)
            assertEquals(1, decoded.branches.size)
            assertEquals("only-branch", decoded.branches[0].name)
        }

        @Test
        fun `Parallel body with multiple named branches represents Named`() {
            val named = StageBody.Parallel(
                branches = listOf(
                    StageNode(id = StageId("linux-amd64"), name = "linux-amd64", body = StageBody.Steps(steps = emptyList())),
                    StageNode(id = StageId("linux-arm64"), name = "linux-arm64", body = StageBody.Steps(steps = emptyList())),
                    StageNode(id = StageId("macos-amd64"), name = "macos-amd64", body = StageBody.Steps(steps = emptyList())),
                ),
            )
            assertEquals(3, named.branches.size)

            val encoded = json.encodeToString(StageBody.Parallel.serializer(), named)
            val decoded = json.decodeFromString(StageBody.Parallel.serializer(), encoded)
            assertEquals(listOf("linux-amd64", "linux-arm64", "macos-amd64"),
                decoded.branches.map { it.name })
        }
    }

    @Nested
    @DisplayName("Nested blocks preserve shape through recursion")
    inner class NestedBlocks {

        @Test
        fun `BlockStepNode nesting None-within-Single preserves both shapes`() {
            val inner = BlockStepNode(
                id = StepId("inner-none"),
                pluginStepId = PluginStepId("core.dir"),
                payload = VersionedStepPayload("dsl-v1", """{"path":"/tmp"}"""),
                body = emptyList(),
            )
            val outer = BlockStepNode(
                id = StepId("outer-single"),
                pluginStepId = PluginStepId("core.stage"),
                payload = VersionedStepPayload("dsl-v1", """{"stages":[]}"""),
                body = listOf(inner),
            )

            assertEquals(1, outer.body.size, "outer Single")
            assertEquals(0, (outer.body[0] as BlockStepNode).body.size, "inner None")

            val encoded = json.encodeToString(BlockStepNode.serializer(), outer)
            val decoded = json.decodeFromString(BlockStepNode.serializer(), encoded)
            assertEquals(1, decoded.body.size)
            val decodedInner = decoded.body[0] as BlockStepNode
            assertEquals(0, decodedInner.body.size)
        }
    }

    @Nested
    @DisplayName("StepId naming is authoritative for Named bodies")
    inner class StepIdAuthoritative {

        @Test
        fun `every StepNode carries an id used as its body-position key`() {
            val none = OpaqueStepNode(
                id = StepId("named-via-id"),
                pluginStepId = PluginStepId("core.echo"),
                payload = VersionedStepPayload("dsl-v1", """{"text":"x"}"""),
            )
            val encoded = json.encodeToString(OpaqueStepNode.serializer(), none)
            assertNotNull(encoded)
            assertTrue(encoded.contains("named-via-id"),
                "id MUST be the canonical body-position key in the IR")
        }
    }
}
