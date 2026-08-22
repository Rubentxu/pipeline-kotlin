package com.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

class FArch003ExperimentalScriptingContainmentTest {

    private val scriptingExperimentalToken = "kotlin.script.experimental"

    @Test
    fun `happy path — no violations at base`() {
        val root = ScannerSupport.v2Root()
        val findings = mutableListOf<Finding>()

        for (file in ScannerSupport.walkKotlinFiles(root)) {
            val relPath = file.toString()
            // Allow under pipeline-scripting-api and under pipeline-architecture-tests
            if (relPath.contains("/pipeline-scripting-api/") ||
                relPath.contains("/pipeline-architecture-tests/src/test/kotlin")) {
                continue
            }
            val lines = Files.readAllLines(file)
            val pattern = Pattern.compile("^import\\s+${Pattern.quote(scriptingExperimentalToken)}(\\..+)?\\s*$")
            for ((lineIdx, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                if (pattern.matcher(line).find()) {
                    findings.add(Finding(file, lineIdx + 1, scriptingExperimentalToken, line))
                }
            }
        }

        assertTrue(findings.isEmpty(), "No file outside pipeline-scripting-api may import kotlin.script.experimental: $findings")
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `scanner rejects the synthetic violation`() {
            // Write fixture inside a synthetic pipeline-application path (outside allow-list)
            val syntheticModule = tempDir.resolve("pipeline-application/src/main/kotlin")
            syntheticModule.toFile().mkdirs()
            val fixture = syntheticModule.resolve("Baz.kt")
            fixture.toFile().writeText("package com.example\nimport kotlin.script.experimental.jvm.JvmScriptCompilationConfiguration\n")

            val findings = mutableListOf<Finding>()
            val pattern = Pattern.compile("^import\\s+${Pattern.quote(scriptingExperimentalToken)}(\\..+)?\\s*$")
            for (file in ScannerSupport.walkKotlinFiles(tempDir)) {
                val lines = Files.readAllLines(file)
                for ((lineIdx, line) in lines.withIndex()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                    if (pattern.matcher(line).find()) {
                        findings.add(Finding(file, lineIdx + 1, scriptingExperimentalToken, line))
                    }
                }
            }

            assertTrue(findings.isNotEmpty(), "Scanner must detect kotlin.script.experimental import outside scripting module")
        }
    }
}
