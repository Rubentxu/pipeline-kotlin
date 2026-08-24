package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FArch004NoCompilerPluginRequirementTest {

    private val forbiddenPattern = "pipeline-steps-system"

    @Test
    fun `static leg — happy path no violations at base`() {
        val root = ScannerSupport.v2Root()
        val findings = ScannerSupport.findBuildSubstring(root, forbiddenPattern)
        assertTrue(findings.isEmpty(), "No build file may contain '$forbiddenPattern': $findings")
    }

    @Test
    fun `runtime leg — happy path no violations at base`() {
        val root = ScannerSupport.v2Root()
        val snapshots = ScannerSupport.loadRuntimeClasspathSnapshots(root)
        val allFindings = mutableListOf<Finding>()

        for ((module, jars) in snapshots) {
            for (jar in jars) {
                if (jar.contains(forbiddenPattern)) {
                    allFindings.add(Finding(root.resolve(module), 0, jar, jar))
                }
            }
        }

        assertTrue(allFindings.isEmpty(), "No runtime classpath jar may contain '$forbiddenPattern': $allFindings")
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `static fixture rejected`() {
            val fixture = tempDir.resolve("build.gradle.kts")
            fixture.toFile().writeText("""
                plugins { kotlin("jvm") }
                // probe
                pipeline-steps-system
            """.trimIndent())

            val findings = ScannerSupport.findBuildSubstring(tempDir, forbiddenPattern)

            assertTrue(findings.isNotEmpty(), "Scanner must detect 'pipeline-steps-system' in fixture")
        }

        @Test
        fun `runtime fixture rejected`() {
            // Create snapshots for all four modules (fail-closed reader requires all four to exist)
            val modules = listOf("pipeline-domain", "pipeline-application", "pipeline-scripting-api", "pipeline-testkit")
            for (module in modules) {
                val moduleDir = tempDir.resolve("$module/build/fitness")
                moduleDir.toFile().mkdirs()
                val snapshotFile = moduleDir.resolve("$module-runtime-classpath.txt")
                val content = if (module == "pipeline-application") {
                    // pipeline-application contains the forbidden jar
                    """
                    |kotlin-stdlib-2.4.10.jar
                    |kotlinx-coroutines-core-1.9.0.jar
                    |pipeline-steps-system-runtime-0.3.0.jar
                    |pipeline-domain-api-0.3.0.jar
                    """.trimMargin()
                } else {
                    // Other modules are clean
                    ""
                }
                snapshotFile.toFile().writeText(content)
            }

            val snapshots = ScannerSupport.loadRuntimeClasspathSnapshots(tempDir)
            val allFindings = mutableListOf<Finding>()

            for ((module, jars) in snapshots) {
                for (jar in jars) {
                    if (jar.contains(forbiddenPattern)) {
                        allFindings.add(Finding(Path.of(module), 0, jar, jar))
                    }
                }
            }

            assertTrue(allFindings.isNotEmpty(), "Scanner must detect 'pipeline-steps-system' in runtime snapshot")
        }
    }
}
