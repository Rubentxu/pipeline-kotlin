package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * UAT / Comp / 003 — CredentialsId and CredentialsRef are resolvable via
 * default imports and the script cache key is stable across evaluations.
 *
 * Covers:
 *  - CR-CLASS-001: default-import resolves CredentialsId
 *  - CR-CLASS-002: default-import resolves CredentialsId + CredentialsRef pair
 *  - CR-CLASS-005: cacheKey is byte-identical between two identical evaluations
 *    (INV-CACHEKEY-STABLE: CacheKey.sha256Hex does NOT include defaultImports)
 */
@Timeout(60)
class UatComp003CredentialsDefaultImportTest {

    private val scriptingHost: Kotlin24ScriptingHost = Kotlin24ScriptingHost()

    @Test
    fun `default-import resolves CredentialsId`() {
        // CR-CLASS-001: inline script using CredentialsId via default import
        val scriptText = """
            val id = CredentialsId("test-id")
            pipeline {
                stages {
                    stage("s") {
                        sh("echo id=${'$'}id")
                    }
                }
            }
        """.trimIndent()

        // updateClasspath REPLACES the host classpath, so we must include both
        // the domain JAR (CredentialsId/CredentialsRef) and the DSL API JAR
        // (pipeline/stages/stage/sh). Using absolute path for domain JAR since
        // it is a direct implementation dep of pipeline-scripting-kotlin24.
        val domainJar = "/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-domain/build/libs/pipeline-domain-0.1.0-SNAPSHOT.jar"
        val dslJar = ScriptDefinition.dslApiJar()
        val fullClasspath = buildList {
            add(domainJar)
            if (dslJar != null) add(dslJar)
        }
        val definition = ScriptDefinition.inline(text = scriptText, classpath = fullClasspath)
        val result = scriptingHost.compile(definition)

        assertTrue(result.isSuccess, "Expected successful compilation: ${result.diagnostics}")
        assertTrue(result.diagnostics.isEmpty(), "Expected no diagnostics: ${result.diagnostics}")
        assertNotNull(result.value, "Expected a script instance to be returned")

        // Verify no ClassNotFoundException in diagnostics
        val classNotFoundMessages = result.diagnostics.map { it.message }
            .filter { it.contains("ClassNotFoundException") || it.contains("CredentialsId") }
        assertTrue(classNotFoundMessages.isEmpty(),
            "Should not have ClassNotFoundException for CredentialsId: $classNotFoundMessages")
    }

    @Test
    fun `default-import resolves CredentialsId + CredentialsRef pair with stable cacheKey`() {
        // CR-CLASS-002 + CR-CLASS-005: both types compile and cache key is stable
        // CredentialsRef(value class) wraps CredentialsId — must construct correctly:
        // val ref = CredentialsRef(CredentialsId("test-ref"))
        val scriptText = """
            val id = CredentialsId("test-id")
            val ref = CredentialsRef(CredentialsId("test-ref"))
            pipeline {
                stages {
                    stage("s") {
                        sh("echo id=${'$'}id ref=${'$'}ref")
                    }
                }
            }
        """.trimIndent()

        // updateClasspath REPLACES the host classpath — must include both domain + DSL JARs
        val domainJar = "/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-domain/build/libs/pipeline-domain-0.1.0-SNAPSHOT.jar"
        val dslJar = ScriptDefinition.dslApiJar()
        val fullClasspath = buildList {
            add(domainJar)
            if (dslJar != null) add(dslJar)
        }
        val definition = ScriptDefinition.inline(text = scriptText, classpath = fullClasspath)

        val result1 = scriptingHost.compile(definition)
        val result2 = scriptingHost.compile(definition)

        // CR-CLASS-002: both compile successfully
        assertTrue(result1.isSuccess, "First compile must succeed: ${result1.diagnostics}")
        assertTrue(result1.diagnostics.isEmpty(), "First compile must have no diagnostics: ${result1.diagnostics}")
        assertTrue(result2.isSuccess, "Second compile must succeed: ${result2.diagnostics}")
        assertTrue(result2.diagnostics.isEmpty(), "Second compile must have no diagnostics: ${result2.diagnostics}")

        // CR-CLASS-005: cacheKey is byte-identical between two identical evaluations
        // INV-CACHEKEY-STABLE: sha256Hex(scriptText, sortedClasspath, kotlinVersion, hostVersion)
        // does NOT include defaultImports — so identical inputs produce identical keys.
        assertEquals(result1.cacheKey.value, result2.cacheKey.value,
            "Cache key must be identical across two identical evaluations (INV-CACHEKEY-STABLE)")
        assertEquals("v1", result1.cacheKey.version,
            "Cache key version must be v1")
        assertEquals("v1", result2.cacheKey.version,
            "Cache key version must be v1")
    }
}
