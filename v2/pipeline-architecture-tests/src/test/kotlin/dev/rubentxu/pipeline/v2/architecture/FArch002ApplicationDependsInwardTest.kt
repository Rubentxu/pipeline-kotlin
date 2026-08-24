package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

class FArch002ApplicationDependsInwardTest {

    private val allowedThirdParty = setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    )

    @Test
    fun `happy path — no violations at base`() {
        val root = ScannerSupport.v2Root()
        val buildFile = root.resolve("pipeline-application/build.gradle.kts")

        if (!buildFile.toFile().exists()) return

        val lines = Files.readAllLines(buildFile)

        // Count implementation(project(":pipeline-domain")) lines
        val projectDepPattern = Pattern.compile("""implementation\s*\(\s*project\s*\(\s*"[^"]*:pipeline-domain"*\s*\)\s*\)""")
        val domainDepCount = lines.count { projectDepPattern.matcher(it).find() }
        assertEquals(1, domainDepCount, "Application must have exactly one implementation(project(\":pipeline-domain\"))")

        // Check no unallowed third-party deps
        val unallowedFindings = ScannerSupport.findUnallowedImplementation(buildFile, allowedThirdParty)
        assertTrue(unallowedFindings.isEmpty(), "Application must not have unallowed third-party deps: $unallowedFindings")
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
                dependencies { implementation("io.ktor:ktor-client-core:2.3.0") }
            """.trimIndent())

            val findings = ScannerSupport.findUnallowedImplementation(fixture, allowedThirdParty)

            assertTrue(findings.isNotEmpty(), "Scanner must detect unallowed third-party dependency in fixture")
            val finding = findings.first()
            assertEquals("io.ktor:ktor-client-core", finding.token)
        }
    }
}
