package dev.rubentxu.pipeline.v2.sdk.runtime

import dev.rubentxu.pipeline.v2.sdk.LspMetadata
import dev.rubentxu.pipeline.v2.sdk.LspParameter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LspMetadataJsonSerializationTest {

    @Test
    fun toJsonProducesValidJsonString() {
        val metadata = LspMetadata(
            schema = "pipeline.dev/lsp/v1",
            stepId = "core.echo",
            name = "echo",
            parameters = listOf(
                LspParameter("context", "StepContext", true, 0),
                LspParameter("message", "kotlin.String", true, 1),
                LspParameter("sink", "EventSink", true, 2),
                LspParameter("stepIndex", "kotlin.Int", true, 3),
            ),
            location = "CONTROLLER",
            replayPolicy = "MEMOIZED",
            failureKindBridge = "INFRASTRUCTURE",
            jenkinsSurface = "echo|workflow-durable-task-step|F3",
        )

        val json = metadata.toJson()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"schema\":\"pipeline.dev/lsp/v1\""))
        assertTrue(json.contains("\"stepId\":\"core.echo\""))
        assertTrue(json.contains("\"name\":\"echo\""))
        assertTrue(json.contains("\"location\":\"CONTROLLER\""))
        assertTrue(json.contains("\"replayPolicy\":\"MEMOIZED\""))
        assertTrue(json.contains("\"jenkinsSurface\":\"echo|workflow-durable-task-step|F3\""))
    }

    @Test
    fun fromJsonParsesValidJsonString() {
        val json = """{"schema":"pipeline.dev/lsp/v1","stepId":"core.sh","name":"sh","parameters":[],"location":"WORKER","replayPolicy":"RERUN","failureKindBridge":"PROCESS","jenkinsSurface":"sh|workflow-durable-task-step|F3"}"""

        val metadata = LspMetadata.fromJson(json)
        assertNotNull(metadata)
        assertEquals("pipeline.dev/lsp/v1", metadata!!.schema)
        assertEquals("core.sh", metadata.stepId)
        assertEquals("sh", metadata.name)
        assertEquals("WORKER", metadata.location)
        assertEquals("RERUN", metadata.replayPolicy)
        assertEquals("PROCESS", metadata.failureKindBridge)
        assertEquals("sh|workflow-durable-task-step|F3", metadata.jenkinsSurface)
    }

    @Test
    fun roundTripToJsonAndFromJsonPreservesData() {
        val original = LspMetadata(
            schema = "pipeline.dev/lsp/v1",
            stepId = "core.echo",
            name = "echo",
            parameters = listOf(
                LspParameter("context", "StepContext", true, 0),
                LspParameter("message", "kotlin.String", true, 1),
            ),
            location = "CONTROLLER",
            replayPolicy = "MEMOIZED",
            failureKindBridge = "INFRASTRUCTURE",
            jenkinsSurface = "echo|workflow-durable-task-step|F3",
        )

        val json = original.toJson()
        val parsed = LspMetadata.fromJson(json)

        assertNotNull(parsed)
        assertEquals(original.schema, parsed!!.schema)
        assertEquals(original.stepId, parsed.stepId)
        assertEquals(original.name, parsed.name)
        assertEquals(original.location, parsed.location)
        assertEquals(original.replayPolicy, parsed.replayPolicy)
        assertEquals(original.failureKindBridge, parsed.failureKindBridge)
        assertEquals(original.jenkinsSurface, parsed.jenkinsSurface)
        assertEquals(original.parameters.size, parsed.parameters.size)
    }

    @Test
    fun fromJsonReturnsNullForMalformedJson() {
        val malformed = "{ this is not valid json }"
        val result = LspMetadata.fromJson(malformed)
        assertEquals(null, result)
    }

    @Test
    fun schemaVersionConstantIsCorrect() {
        assertEquals("pipeline.dev/lsp/v1", LspMetadata.SCHEMA_VERSION)
    }

    @Test
    fun toJsonEscapesSpecialCharactersInStrings() {
        val metadata = LspMetadata(
            schema = "pipeline.dev/lsp/v1",
            stepId = "core.echo",
            name = "echo",
            parameters = emptyList(),
            location = "CONTROLLER",
            replayPolicy = "MEMOIZED",
            failureKindBridge = "INFRASTRUCTURE",
            jenkinsSurface = "test with\nnewline",
        )

        val json = metadata.toJson()
        assertTrue(json.contains("\\n"))
    }
}
