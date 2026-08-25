package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ShOptionsTest {

    @Test
    fun `ShOptions empty has sensible defaults`() {
        val options = ShOptions.EMPTY
        assertNotNull(options.workspaceRoot)
        assertFalse(options.captureStdout)
        assertNull(options.timeoutMs)
        assertTrue(options.env.isEmpty())
    }

    @Test
    fun `ShOptions can be constructed with all values`() {
        val tempDir = Files.createTempDirectory("test")
        val options = ShOptions(
            workspaceRoot = tempDir,
            captureStdout = true,
            timeoutMs = 60000L,
            env = mapOf("FOO" to "bar"),
        )
        assertEquals(tempDir, options.workspaceRoot)
        assertTrue(options.captureStdout)
        assertEquals(60000L, options.timeoutMs)
        assertEquals("bar", options.env["FOO"])
    }

    @Test
    fun `ShOptions captureStdout false is default for L1`() {
        val tempDir = Files.createTempDirectory("test")
        val options = ShOptions(
            workspaceRoot = tempDir,
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
        )
        assertFalse(options.captureStdout)
    }

    @Test
    fun `ShOptions timeoutMs can be null`() {
        val tempDir = Files.createTempDirectory("test")
        val options = ShOptions(
            workspaceRoot = tempDir,
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
        )
        assertNull(options.timeoutMs)
    }

    @Test
    fun `ShOptions env can be empty`() {
        val tempDir = Files.createTempDirectory("test")
        val options = ShOptions(
            workspaceRoot = tempDir,
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
        )
        assertTrue(options.env.isEmpty())
    }

    @Test
    fun `ShOptions copy preserves values`() {
        val tempDir = Files.createTempDirectory("test")
        val original = ShOptions(
            workspaceRoot = tempDir,
            captureStdout = true,
            timeoutMs = 30000L,
            env = mapOf("KEY" to "value"),
        )
        val copy = original.copy()
        assertEquals(original.workspaceRoot, copy.workspaceRoot)
        assertEquals(original.captureStdout, copy.captureStdout)
        assertEquals(original.timeoutMs, copy.timeoutMs)
        assertEquals(original.env, copy.env)
    }
}
