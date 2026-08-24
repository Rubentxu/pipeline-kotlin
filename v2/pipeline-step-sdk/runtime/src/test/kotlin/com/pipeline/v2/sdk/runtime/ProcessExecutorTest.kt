package com.pipeline.v2.sdk.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for ProcessExecutor - argv safety + exitCode capture.
 */
class ProcessExecutorTest {

    @Test
    fun `execute captures stdout`() {
        val result = ProcessExecutor().execute(listOf("echo", "hello"))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("hello"))
    }

    @Test
    fun `execute captures stderr`() {
        val result = ProcessExecutor().execute(listOf("sh", "-c", "echo error >&2"))
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("error"))
    }

    @Test
    fun `execute returns non-zero exit code on failure`() {
        val result = ProcessExecutor().execute(listOf("sh", "-c", "exit 42"))
        assertEquals(42, result.exitCode)
    }

    @Test
    fun `execute with argv list - echo hello`() {
        val result = ProcessExecutor().execute(listOf("echo", "hello from argv"))
        assertNotNull(result)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `execute handles missing command gracefully`() {
        val result = ProcessExecutor().execute(listOf("nonexistent-command-xyz"))
        assertTrue(result.exitCode != 0)
    }
}
