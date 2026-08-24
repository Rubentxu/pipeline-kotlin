package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FArch003ExperimentalScriptingContainmentTest {

    private val scriptingExperimentalToken = "kotlin.script.experimental"

    @Test
    fun `happy path — no violations at base`() {
        val root = ScannerSupport.v2Root()
        val allowList = listOf(
            "/pipeline-scripting-api/",
            "/pipeline-scripting-kotlin24/",
            "/pipeline-events/",
            "/pipeline-architecture-tests/src/test/kotlin",
        )
        val findings = SourceScanner.findImports(root, listOf(scriptingExperimentalToken), allowList)
        assertTrue(findings.isEmpty(), "No file outside pipeline-scripting-api may import kotlin.script.experimental: $findings")
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `scanner rejects the synthetic violation`() {
            val syntheticModule = tempDir.resolve("pipeline-application/src/main/kotlin")
            syntheticModule.toFile().mkdirs()
            val fixture = syntheticModule.resolve("Baz.kt")
            fixture.toFile().writeText("package com.example\nimport kotlin.script.experimental.jvm.JvmScriptCompilationConfiguration\n")

            val findings = SourceScanner.findImports(tempDir, listOf(scriptingExperimentalToken))
            assertTrue(findings.isNotEmpty(), "Scanner must detect kotlin.script.experimental import outside scripting module")
        }
    }
}
