package com.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * UAT / Comp / 001 — Script compiles successfully and cache key is stable.
 */
class UatComp001ScriptCompilesTest {

    private val scriptingHost: ScriptingHost = Kotlin24ScriptingHost()

    @Test
    fun `script compiles and returns success`() {
        val scriptPath = Paths.get(
            javaClass.getResource("/hello.kts")!!.toURI()
        )
        val definition = ScriptDefinition.file(scriptPath)

        val result = scriptingHost.compile(definition)

        assertTrue(result.isSuccess, "Expected successful compilation: ${result.diagnostics}")
        assertTrue(result.diagnostics.isEmpty(), "Expected no diagnostics: ${result.diagnostics}")
        assertNotNull(result.value)
    }

    @Test
    fun `cache key is stable across two evaluations`() {
        val scriptPath = Paths.get(
            javaClass.getResource("/hello.kts")!!.toURI()
        )
        val definition = ScriptDefinition.file(scriptPath)

        val result1 = scriptingHost.compile(definition)
        val result2 = scriptingHost.compile(definition)

        assertEquals(result1.cacheKey, result2.cacheKey,
            "Cache key must be identical across evaluations")
    }
}
