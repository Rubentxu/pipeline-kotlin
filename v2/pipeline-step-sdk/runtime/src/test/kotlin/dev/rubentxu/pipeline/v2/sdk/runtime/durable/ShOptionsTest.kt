package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
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
    fun `ShOptions can be constructed with typed env`() {
        val tempDir = Files.createTempDirectory("test")
        val options = ShOptions(
            workspaceRoot = tempDir,
            captureStdout = true,
            timeoutMs = 60000L,
            env = mapOf("FOO" to SecretHandle.plain("bar")),
        )
        assertEquals(tempDir, options.workspaceRoot)
        assertTrue(options.captureStdout)
        assertEquals(60000L, options.timeoutMs)
        assertEquals("bar", options.env["FOO"]?.materialize())
    }

    @Test
    fun `ShOptions from factory converts Map String String to typed env`() {
        val tempDir = Files.createTempDirectory("test")
        val options = ShOptions.from(
            env = mapOf("FOO" to "bar", "BAZ" to "qux")
        ).copy(
            workspaceRoot = tempDir,
            captureStdout = false,
            timeoutMs = null,
        )
        
        // Verify the env contains SecretHandle instances
        assertEquals(2, options.env.size)
        assertTrue(options.env.containsKey("FOO"))
        assertTrue(options.env.containsKey("BAZ"))
        
        // Verify the values are correct when materialized
        assertEquals("bar", options.env["FOO"]?.materialize())
        assertEquals("qux", options.env["BAZ"]?.materialize())
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
            env = mapOf("KEY" to SecretHandle.plain("value")),
        )
        val copy = original.copy()
        assertEquals(original.workspaceRoot, copy.workspaceRoot)
        assertEquals(original.captureStdout, copy.captureStdout)
        assertEquals(original.timeoutMs, copy.timeoutMs)
        assertEquals(original.env["KEY"]?.materialize(), copy.env["KEY"]?.materialize())
    }
}
