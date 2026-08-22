package com.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FArch001DomainFrameworkFreeTest {

    private val forbiddenTokens = setOf("jenkins", "kubernetes", "koin", "docker", "flyway", "exposed", "jooq", "hikari")

    @Test
    fun `happy path — no violations at base`() {
        val root = ScannerSupport.v2Root()
        val domainSrc = root.resolve("pipeline-domain/src")
        val buildFile = root.resolve("pipeline-domain/build.gradle.kts")

        val importFindings = if (domainSrc.toFile().exists()) {
            ScannerSupport.findImports(domainSrc, forbiddenTokens)
        } else {
            emptyList()
        }

        val buildFindings = if (buildFile.toFile().exists()) {
            ScannerSupport.findUnallowedImplementation(buildFile, forbiddenTokens)
        } else {
            emptyList()
        }

        assertTrue(importFindings.isEmpty(), "Domain source must not import forbidden frameworks: $importFindings")
        assertTrue(buildFindings.isEmpty(), "Domain build must not depend on forbidden frameworks: $buildFindings")
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `scanner rejects the synthetic violation`() {
            val fixture = tempDir.resolve("Forbidden.kt")
            fixture.toFile().writeText("package com.example\nimport io.kubernetes.client.openapi.apis.CoreV1Api\n")

            val findings = ScannerSupport.findImports(tempDir, forbiddenTokens)

            assertTrue(findings.isNotEmpty(), "Scanner must detect forbidden framework import in fixture")
            val finding = findings.first()
            assertEquals("kubernetes", finding.token)
            assertEquals("Forbidden.kt", finding.file.fileName.toString())
        }
    }
}
