package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FArch011V2NoCompileExcludesTest {

    @Test
    fun `happy path — no violations at base`() {
        val root = ScannerSupport.v2Root()
        val findings = ScannerSupport.findExcludeCalls(root)
        assertTrue(findings.isEmpty(), "No build file may contain 'exclude(': $findings")
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `scanner rejects the synthetic violation`() {
            val fixture = tempDir.resolve("build.gradle.kts")
            fixture.toFile().writeText("""
                plugins { kotlin("jvm") }
                tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
                    exclude("**/Legacy.kt")
                }
            """.trimIndent())

            val findings = ScannerSupport.findExcludeCalls(tempDir)

            assertTrue(findings.isNotEmpty(), "Scanner must detect 'exclude(' in fixture")
            val finding = findings.first()
            assertTrue(finding.excerpt.contains("exclude("))
        }
    }
}
