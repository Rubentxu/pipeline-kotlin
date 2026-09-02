package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MapPipelineCompilerTest {

    @Test
    fun `compile returns the registered definition when the source matches a key`() {
        val definition = PipelineDefinition(
            id = DefinitionId("hello"),
            name = "hello",
            version = "0.0.0",
            steps = listOf(
                StepDescriptor(id = "build", type = "sh", configRef = "build.config"),
                StepDescriptor(id = "test", type = "sh", configRef = "test.config"),
            ),
            edges = listOf(Edge("build", "test")),
        )
        val compiler = MapPipelineCompiler(mapOf("hello" to definition))

        val result = compiler.compile("hello")

        assertTrue(result is CompileResult.Success)
        assertEquals(definition, (result as CompileResult.Success).definition)
    }

    @Test
    fun `compile returns Failure when the source does not match any registered key`() {
        val compiler = MapPipelineCompiler(emptyMap())

        val result = compiler.compile("missing")

        assertTrue(result is CompileResult.Failure)
        val failure = result as CompileResult.Failure
        assertEquals(1, failure.diagnostics.size)
        val diagnostic = failure.diagnostics.single()
        assertEquals(1, diagnostic.line)
        assertEquals(1, diagnostic.column)
        assertEquals(PipelineDiagnostic.Severity.ERROR, diagnostic.severity)
    }

    @Test
    fun `compile returns Failure for blank sources`() {
        val compiler = MapPipelineCompiler(emptyMap())

        listOf("", " ", "\t", "\n").forEach { blank ->
            val result = compiler.compile(blank)
            assertTrue(
                result is CompileResult.Failure,
                "blank source '$blank' must produce a Failure",
            )
        }
    }

    @Test
    fun `compile does NOT echo the source back in the diagnostic message`() {
        val compiler = MapPipelineCompiler(emptyMap())
        val secret = "AKIA-some-secret-credential"

        val result = compiler.compile(secret)

        assertTrue(result is CompileResult.Failure)
        val message = (result as CompileResult.Failure).diagnostics.single().message
        assertTrue(
            !message.contains(secret),
            "diagnostic message must not leak the supplied source identifier; got: $message",
        )
    }

    @Test
    fun `backing map is defensively copied at construction`() {
        val mutable = mutableMapOf<String, PipelineDefinition>()
        val compiler = MapPipelineCompiler(mutable)
        mutable["late"] = PipelineDefinition(
            id = DefinitionId("late"),
            name = "late",
            version = "0.0.0",
        )

        val result = compiler.compile("late")

        assertTrue(result is CompileResult.Failure, "late-inserted key must not be visible")
    }

    @Test
    fun `empty factory produces a compiler that fails on every input`() {
        val compiler = MapPipelineCompiler.empty()

        val result = compiler.compile("anything")

        assertTrue(result is CompileResult.Failure)
        assertEquals(1, (result as CompileResult.Failure).diagnostics.size)
    }

    @Test
    fun `CompileResult Failure requires at least one diagnostic`() {
        val ex = assertThrows<IllegalArgumentException> {
            CompileResult.Failure(emptyList())
        }
        assertNotNull(ex.message)
    }

    @Test
    fun `PipelineDiagnostic requires line to be at least 1 when present`() {
        val ex = assertThrows<IllegalArgumentException> {
            PipelineDiagnostic(line = 0, column = 1, message = "x")
        }
        assertNotNull(ex.message)
    }

    @Test
    fun `PipelineDiagnostic accepts null line and column for global diagnostics`() {
        val diag = PipelineDiagnostic(line = null, column = null, message = "global")

        assertNull(diag.line)
        assertNull(diag.column)
    }
}
