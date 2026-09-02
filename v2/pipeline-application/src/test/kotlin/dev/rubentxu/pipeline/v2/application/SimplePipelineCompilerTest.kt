package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CompileResult
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Edge
import dev.rubentxu.pipeline.v2.domain.EdgeKind
import dev.rubentxu.pipeline.v2.domain.PipelineDiagnostic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimplePipelineCompilerTest {

    private val compiler = SimplePipelineCompiler()

    @Test
    fun `minimal source with only name and version compiles successfully`() {
        val result = compiler.compile(
            """
            name hello
            version 0.0.0
            """.trimIndent()
        )

        assertTrue(result is CompileResult.Success)
        val def = (result as CompileResult.Success).definition
        assertEquals(DefinitionId("hello"), def.id)
        assertEquals("hello", def.name)
        assertEquals("0.0.0", def.version)
        assertEquals(emptyList<String>(), def.steps.map { it.id })
        assertEquals(emptyList<Edge>(), def.edges)
    }

    @Test
    fun `step directives produce StepDescriptors with configRef defaulting to id`() {
        val result = compiler.compile(
            """
            name hello
            version 0.0.0
            step sh build
            step sh test test.config
            """.trimIndent()
        )

        assertTrue(result is CompileResult.Success)
        val def = (result as CompileResult.Success).definition
        assertEquals(2, def.steps.size)
        assertEquals("build", def.steps[0].id)
        assertEquals("sh", def.steps[0].type)
        assertEquals("build", def.steps[0].configRef, "configRef defaults to the step id when omitted")
        assertEquals("test.config", def.steps[1].configRef)
    }

    @Test
    fun `edge directives produce Edges with SEQUENTIAL default`() {
        val result = compiler.compile(
            """
            name hello
            version 0.0.0
            step sh build
            step sh test
            edge build test
            """.trimIndent()
        )

        assertTrue(result is CompileResult.Success)
        val def = (result as CompileResult.Success).definition
        assertEquals(listOf(Edge("build", "test", EdgeKind.SEQUENTIAL)), def.edges)
    }

    @Test
    fun `PARALLEL and CONDITIONAL edge kinds are accepted by the compiler`() {
        val result = compiler.compile(
            """
            name hello
            version 0.0.0
            step sh a
            step sh b
            edge a b PARALLEL
            edge b a CONDITIONAL
            """.trimIndent()
        )

        assertTrue(result is CompileResult.Success)
        val def = (result as CompileResult.Success).definition
        assertEquals(EdgeKind.PARALLEL, def.edges[0].kind)
        assertEquals(EdgeKind.CONDITIONAL, def.edges[1].kind)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val result = compiler.compile(
            """
            # header comment
            name hello
            # inline comment
            version 0.0.0

            # trailing comment
            """.trimIndent()
        )

        assertTrue(result is CompileResult.Success)
    }

    @Test
    fun `missing name directive produces a global diagnostic`() {
        val result = compiler.compile("version 0.0.0")

        assertTrue(result is CompileResult.Failure)
        val failure = result as CompileResult.Failure
        val missingName = failure.diagnostics.single { it.message.contains("name") }
        assertEquals(null, missingName.line)
        assertEquals(null, missingName.column)
        assertEquals(PipelineDiagnostic.Severity.ERROR, missingName.severity)
    }

    @Test
    fun `missing version directive produces a global diagnostic`() {
        val result = compiler.compile("name hello")

        assertTrue(result is CompileResult.Failure)
        val failure = result as CompileResult.Failure
        assertTrue(failure.diagnostics.any { it.message.contains("version") })
    }

    @Test
    fun `duplicate step ids are rejected`() {
        val result = compiler.compile(
            """
            name hello
            version 0.0.0
            step sh build
            step sh build
            """.trimIndent()
        )

        assertTrue(result is CompileResult.Failure)
        val failure = result as CompileResult.Failure
        assertTrue(failure.diagnostics.any { it.message.contains("duplicate step id 'build'") })
    }

    @Test
    fun `unknown directive produces a per-line diagnostic`() {
        val result = compiler.compile(
            """
            name hello
            version 0.0.0
            mystery hello
            """.trimIndent()
        )

        assertTrue(result is CompileResult.Failure)
        val failure = result as CompileResult.Failure
        val unknown = failure.diagnostics.single { it.message.contains("mystery") }
        assertEquals(3, unknown.line)
        assertEquals(1, unknown.column)
    }

    @Test
    fun `unknown edge kind produces a diagnostic with the offending token`() {
        val result = compiler.compile(
            """
            name hello
            version 0.0.0
            step sh build
            step sh test
            edge build test WHATEVER
            """.trimIndent()
        )

        assertTrue(result is CompileResult.Failure)
        val failure = result as CompileResult.Failure
        assertTrue(failure.diagnostics.any { it.message.contains("WHATEVER") })
    }

    @Test
    fun `multiple errors are collected in a single Failure`() {
        val result = compiler.compile(
            """
            name
            version
            step
            edge a
            """.trimIndent()
        )

        assertTrue(result is CompileResult.Failure)
        val failure = result as CompileResult.Failure
        // name, version, step, edge — four directive-level errors plus the
        // duplicate-name check does not apply because name was never set.
        // The compiler must collect all of them in one pass.
        assertTrue(failure.diagnostics.size >= 4, "expected >= 4 diagnostics, got ${failure.diagnostics.size}")
    }
}
