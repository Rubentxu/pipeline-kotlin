package com.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * UAT / Comp / 002 — broken script produces non-empty diagnostics with
 * source-mapped line and path information.
 */
class UatComp002ErrorSourceMappedTest {

    private val scriptingHost: ScriptingHost = Kotlin24ScriptingHost()

    @Test
    fun `broken script yields error diagnostics`() {
        val scriptPath = Paths.get(
            javaClass.getResource("/broken.pipeline.kts")!!.toURI()
        )
        val definition = ScriptDefinition.file(scriptPath)

        val result = scriptingHost.compile(definition)

        assertFalse(result.isSuccess, "Expected compilation failure for broken script")
        assertTrue(result.diagnostics.isNotEmpty(), "Expected at least one diagnostic")

        val errors = result.diagnostics.filter { it.severity == ScriptDiagnosticSeverity.ERROR }
        assertTrue(errors.isNotEmpty(), "Expected at least one ERROR diagnostic: ${result.diagnostics}")
    }

    @Test
    fun `diagnostic line is greater than zero`() {
        val scriptPath = Paths.get(
            javaClass.getResource("/broken.pipeline.kts")!!.toURI()
        )
        val definition = ScriptDefinition.file(scriptPath)

        val result = scriptingHost.compile(definition)

        val hasPositiveLine = result.diagnostics.any { it.line > 0 }
        assertTrue(hasPositiveLine, "Expected at least one diagnostic with line > 0: ${result.diagnostics}")
    }

    @Test
    fun `diagnostic path references the broken script`() {
        val scriptPath = Paths.get(
            javaClass.getResource("/broken.pipeline.kts")!!.toURI()
        )
        val definition = ScriptDefinition.file(scriptPath)
        val scriptName = scriptPath.fileName.toString()

        val result = scriptingHost.compile(definition)

        val hasScriptPath = result.diagnostics.any { diag ->
            diag.path.contains(scriptName) || diag.path == scriptPath.toString()
        }
        assertTrue(hasScriptPath,
            "Expected diagnostic path to reference '$scriptName': ${result.diagnostics}")
    }
}
