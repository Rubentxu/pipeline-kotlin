package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineIdsTest {
    @Test
    fun `stable model identity types are available in the domain`() {
        val expectedTypes = listOf("StageId", "StepId", "PluginStepId", "AttemptId", "OperationId")
        val missingTypes = expectedTypes.filter { typeName ->
            runCatching { Class.forName("${PipelineIdsTest::class.java.packageName}.$typeName") }.isFailure
        }

        assertTrue(missingTypes.isEmpty(), "Missing stable model identity types: $missingTypes")
    }

    @Test
    fun `stable model identity types reject invalid values`() {
        assertThrows(IllegalArgumentException::class.java) { StageId(" ") }
        assertThrows(IllegalArgumentException::class.java) { StepId("") }
        assertThrows(IllegalArgumentException::class.java) { PluginStepId("\t") }
        assertThrows(IllegalArgumentException::class.java) { AttemptId(-1) }
        assertThrows(IllegalArgumentException::class.java) { OperationId("\n") }
    }

    @Test
    fun `run id generator seam supports deterministic adapters`() {
        val generator = object : RunIdGenerator {
            private var nextValue = 1

            override fun next(): RunId = RunId("run-${nextValue++}")
        }

        assertEquals(RunId("run-1"), generator.next())
        assertEquals(RunId("run-2"), generator.next())
    }

    @Test
    fun `typed ids reject blank values`() {
        assertThrows(IllegalArgumentException::class.java) { RunId(" ") }
        assertThrows(IllegalArgumentException::class.java) { DefinitionId("") }
    }

    @Test
    fun `definition id is deterministic for the same source`() {
        val first = DeterministicIdGenerator.definitionId("Pipelinefile.kts", "echo hello")
        val second = DeterministicIdGenerator.definitionId("Pipelinefile.kts", "echo hello")

        assertEquals(first, second)
        assertEquals(36, first.value.length)
    }

    @Test
    fun `definition id changes when source changes`() {
        val original = DeterministicIdGenerator.definitionId("Pipelinefile.kts", "echo hello")
        val changed = DeterministicIdGenerator.definitionId("Pipelinefile.kts", "echo goodbye")

        assertNotEquals(original, changed)
    }

    @Test
    fun `typed definition id is deterministic`() {
        val input = DefinitionIdentityInput(
            source = "pipeline { stage(\"build\") }",
            compatibilityVersion = "v2",
            semanticInputs = mapOf("target" to "jvm"),
        )

        val first = DeterministicIdGenerator.definitionId(input)
        val second = DeterministicIdGenerator.definitionId(input)

        assertEquals(first, second)
        assertEquals(64, first.value.length)
    }

    @Test
    fun `compatibility version participates in typed definition identity`() {
        val source = "pipeline { stage(\"build\") }"

        val first = DeterministicIdGenerator.definitionId(DefinitionIdentityInput(source, "v1"))
        val second = DeterministicIdGenerator.definitionId(DefinitionIdentityInput(source, "v2"))

        assertNotEquals(first, second)
    }

    @Test
    fun `semantic inputs participate in typed definition identity`() {
        val first = DeterministicIdGenerator.definitionId(
            DefinitionIdentityInput("pipeline {}", "v2", mapOf("target" to "jvm")),
        )
        val second = DeterministicIdGenerator.definitionId(
            DefinitionIdentityInput("pipeline {}", "v2", mapOf("target" to "native")),
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `semantic input insertion order does not affect typed definition identity`() {
        val first = linkedMapOf("target" to "jvm", "profile" to "release")
        val second = linkedMapOf("profile" to "release", "target" to "jvm")

        assertEquals(
            DeterministicIdGenerator.definitionId(DefinitionIdentityInput("pipeline {}", "v2", first)),
            DeterministicIdGenerator.definitionId(DefinitionIdentityInput("pipeline {}", "v2", second)),
        )
    }

    @Test
    fun `source line endings are canonicalized for typed definition identity`() {
        val lf = DeterministicIdGenerator.definitionId(DefinitionIdentityInput("a\nb\n", "v2"))
        val crlf = DeterministicIdGenerator.definitionId(DefinitionIdentityInput("a\r\nb\r\n", "v2"))
        val cr = DeterministicIdGenerator.definitionId(DefinitionIdentityInput("a\rb\r", "v2"))

        assertEquals(lf, crlf)
        assertEquals(lf, cr)
    }

    @Test
    fun `length framing prevents structural collisions from delimiters and line breaks`() {
        val first = DefinitionIdentityInput("a|b\nc", "v2", mapOf("x" to "y|z"))
        val second = DefinitionIdentityInput("a", "b\nc|v2", mapOf("x|y" to "z"))

        assertNotEquals(
            DeterministicIdGenerator.definitionId(first),
            DeterministicIdGenerator.definitionId(second),
        )
    }

    @Test
    fun `typed definition identity rejects blank required values`() {
        assertThrows(IllegalArgumentException::class.java) { DefinitionIdentityInput("\uFEFF\r\n", "v2") }
        assertThrows(IllegalArgumentException::class.java) { DefinitionIdentityInput("pipeline {}", " \t") }
        assertThrows(IllegalArgumentException::class.java) {
            DefinitionIdentityInput("pipeline {}", "v2", mapOf(" \n" to "value"))
        }
    }

    @Test
    fun `legacy definition identity remains compatible`() {
        val id = DeterministicIdGenerator.definitionId("Pipelinefile.kts", "echo hello")

        assertEquals("2f797d972ecd2cd07d0959a263695ea80fec", id.value)
        assertEquals(36, id.value.length)
    }
}
