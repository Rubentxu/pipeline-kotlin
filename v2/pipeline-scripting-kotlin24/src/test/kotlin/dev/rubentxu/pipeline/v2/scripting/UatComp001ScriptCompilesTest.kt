package dev.rubentxu.pipeline.v2.scripting
import org.junit.jupiter.api.Disabled


import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * UAT / Comp / 001 — Script compiles successfully and cache key is stable.
 */
@Disabled("migrated-pending")
// Migrated-pending per docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md BQ5.
// The harness is the owner of the equivalence proof; the source stays in place
// until that proof arrives and the deletion guard (retirement_guard.py) authorises it.
class UatComp001ScriptCompilesTest {

    private val scriptingHost: ScriptingHost = Kotlin24ScriptingHost()

    @Test
    fun `script compiles and returns success`() {
        val scriptPath = Paths.get(
            javaClass.getResource("/hello.pipeline.kts")!!.toURI()
        )
        val dslJar = ScriptDefinition.dslApiJar()
        val dslClasspath = if (dslJar != null) listOf(dslJar) else emptyList()
        val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)

        val result = scriptingHost.compile(definition)

        assertTrue(result.isSuccess, "Expected successful compilation: ${result.diagnostics}")
        // M1-F.1: the Kotlin compiler confirms `-Xuse-fast-jar-file-system=false`
        // with an INFO diagnostic. The diagnostic is NOT a failure — it is the
        // observable confirmation that the option was accepted. The test asserts
        // only on ERROR/FATAL diagnostics, which are the user-actionable cases.
        val errorDiagnostics = result.diagnostics.filter {
            it.severity == ScriptDiagnosticSeverity.ERROR ||
                it.severity == ScriptDiagnosticSeverity.FATAL
        }
        assertTrue(
            errorDiagnostics.isEmpty(),
            "Expected no ERROR/FATAL diagnostics: $errorDiagnostics",
        )
        assertNotNull(result.value)
    }

    @Test
    fun `cache key is stable across two evaluations`() {
        val scriptPath = Paths.get(
            javaClass.getResource("/hello.pipeline.kts")!!.toURI()
        )
        val dslJar = ScriptDefinition.dslApiJar()
        val dslClasspath = if (dslJar != null) listOf(dslJar) else emptyList()
        val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)

        val result1 = scriptingHost.compile(definition)
        val result2 = scriptingHost.compile(definition)

        assertEquals(result1.cacheKey.value, result2.cacheKey.value,
            "Cache key must be identical across evaluations")
        // The label moved v1 -> v2 when the identity completed to the shape declared by
        // SCRIPTING_COMPILER_SPEC.md §7. See CacheKey.kt and the migration plan in
        // docs/v2/07-uat/CACHE_KEY_V2_MIGRATION_PLAN.md.
        assertEquals("v2", result1.cacheKey.version,
            "Cache key version must be v2")
    }
}
