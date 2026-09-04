package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json

class CompiledPipelineTest {
    @Test
    fun `compiled pipeline retains the explicit stage body shape`() {
        val step = OpaqueStepNode(StepId("compile"), PluginStepId("core.shell"), VersionedStepPayload("v1", "{}"))
        val stage = StageNode(StageId("build"), "Build", body = StageBody.Steps(listOf(step)))
        val pipeline = CompiledPipeline(
            id = DefinitionId("definition"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            stages = listOf(stage),
            pluginLockDigest = Digest("lock"),
        )

        assertEquals(listOf(step), (pipeline.stages.single().body as StageBody.Steps).steps)
    }

    @Test
    fun `compiled pipeline rejects duplicate stage identities`() {
        val stage = StageNode(StageId("build"), "Build", body = StageBody.Steps(emptyList()))

        assertThrows(IllegalArgumentException::class.java) {
            CompiledPipeline(
                id = DefinitionId("definition"),
                source = SourceDescriptor("Pipeline.kts", Digest("source")),
                stages = listOf(stage, stage),
                pluginLockDigest = Digest("lock"),
            )
        }
    }

    @Test
    fun `compiled pipeline round trips through its versioned representation`() {
        val pipeline = CompiledPipeline(
            id = DefinitionId("definition"),
            source = SourceDescriptor("Pipeline.kts", Digest("source")),
            stages = listOf(
                StageNode(
                    StageId("build"),
                    "Build",
                    body = StageBody.Steps(
                        listOf(OpaqueStepNode(StepId("compile"), PluginStepId("core.shell"), VersionedStepPayload("v1", "{}"))),
                    ),
                ),
            ),
            pluginLockDigest = Digest("lock"),
        )

        val encoded = Json.encodeToString(CompiledPipeline.serializer(), pipeline)
        assertEquals(pipeline, Json.decodeFromString(CompiledPipeline.serializer(), encoded))
    }
}
