package com.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FArch004NoCompilerPluginRequirementTest {

    // Used in static-leg fixture; this constant is the expected forbidden pattern itself
    @Suppress("unused")
    private val forbiddenSubstring = "pipeline-steps-system"

    @Test
    fun `static leg — happy path no violations at base`() {
        val root = ScannerSupport.v2Root()
        val findings = ScannerSupport.findBuildSubstring(root, "pipeline-steps-system")
        assertTrue(findings.isEmpty(), "No build file may contain 'pipeline-steps-system': $findings")
    }

    @Test
    fun `runtime leg — happy path no violations at base`() {
        val root = ScannerSupport.v2Root()
        val snapshots = ScannerSupport.loadRuntimeClasspathSnapshots(root)
        val allFindings = mutableListOf<Finding>()

        for ((module, jars) in snapshots) {
            for (jar in jars) {
                if (jar.contains("pipeline-steps-system")) {
                    allFindings.add(Finding(root.resolve(module), 0, jar, jar))
                }
            }
        }

        assertTrue(allFindings.isEmpty(), "No runtime classpath jar may contain 'pipeline-steps-system': $allFindings")
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

            val findings = ScannerSupport.findBuildSubstring(tempDir, "pipeline-steps-system")

            assertTrue(findings.isNotEmpty(), "Scanner must detect 'pipeline-steps-system' in fixture")
        }

        @Test
        fun `runtime fixture rejected`() {
            val syntheticModule = tempDir.resolve("pipeline-application/build/fitness")
            syntheticModule.toFile().mkdirs()
            val snapshotFile = syntheticModule.resolve("pipeline-application-runtime-classpath.txt")
            snapshotFile.toFile().writeText("""
                kotlin-stdlib-2.4.10.jar
                kotlinx-coroutines-core-1.9.0.jar
                pipeline-steps-system-runtime-0.3.0.jar
                pipeline-domain-api-0.3.0.jar
            """.trimIndent())

            val snapshots = ScannerSupport.loadRuntimeClasspathSnapshots(tempDir)
            val allFindings = mutableListOf<Finding>()

            for ((module, jars) in snapshots) {
                for (jar in jars) {
                    if (jar.contains("pipeline-steps-system")) {
                        allFindings.add(Finding(Path.of(module), 0, jar, jar))
                    }
                }
            }

            assertTrue(allFindings.isNotEmpty(), "Scanner must detect 'pipeline-steps-system' in runtime snapshot")
        }
    }
}
