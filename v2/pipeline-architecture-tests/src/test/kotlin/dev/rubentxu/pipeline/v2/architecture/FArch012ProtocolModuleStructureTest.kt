package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FArch012ProtocolModuleStructureTest {

    private val forbiddenDependencyModules = setOf(
        ":pipeline-application",
        ":pipeline-scripting-kotlin24",
        ":pipeline-testkit"
    )

    @Test
    fun `protocol module depends only on allowed modules`() {
        val root = ScannerSupport.v2Root()
        val protocolSrc = root.resolve("pipeline-protocol/src")
        val protocolBuildFile = root.resolve("pipeline-protocol/build.gradle.kts")

        val findings = if (protocolBuildFile.toFile().exists()) {
            val unallowedPattern = Regex("""implementation\s*\(\s*project\(["']([^"']+)["']\s*\)""")
            val lines = protocolBuildFile.toFile().readLines()
            lines.mapIndexedNotNull { idx, line ->
                val match = unallowedPattern.find(line.trim())
                if (match != null) {
                    val module = match.groupValues[1]
                    if (module in forbiddenDependencyModules) {
                        Finding(protocolBuildFile, idx + 1, module, line)
                    } else null
                } else null
            }
        } else {
            emptyList()
        }

        assertTrue(findings.isEmpty(), "Protocol module must not depend on forbidden modules: $findings")
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `scanner rejects forbidden module dependency in fixture`() {
            val fixture = tempDir.resolve("build.gradle.kts")
            fixture.toFile().writeText("""
                dependencies {
                    implementation(project(":pipeline-application"))
                }
            """.trimIndent())

            val unallowedPattern = Regex("""implementation\s*\(\s*project\(["']([^"']+)["']\s*\)""")
            val lines = fixture.toFile().readLines()
            val findings = lines.mapIndexedNotNull { idx, line ->
                val match = unallowedPattern.find(line.trim())
                if (match != null) {
                    val module = match.groupValues[1]
                    if (module in setOf(":pipeline-application")) {
                        Finding(fixture, idx + 1, module, line)
                    } else null
                } else null
            }

            assertTrue(findings.isNotEmpty(), "Scanner must detect forbidden module dependency in fixture")
            val finding = findings.first()
            assertEquals(":pipeline-application", finding.token)
        }
    }
}
