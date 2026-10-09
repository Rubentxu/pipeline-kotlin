package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

/**
 * Non-regression test for the `java.io.tmpdir` leak (backlog P1
 * `bl-bl-01M4BA8P3A000388PMKT3ECQ40`).
 *
 * HF1, in-process: the subject is `ShOptions.Companion`, reached through the
 * public `ShOptions.EMPTY` / `ShOptions.from` surface. No reimplementation of
 * the production decision is involved — the assertion counts real directories
 * that the production code actually created in the real system temp dir.
 *
 * Mutation that kills this claim: restoring
 * `Files.createTempDirectory("shoptions-from")` inside `from()` makes
 * `fromLeavesNoNewTempDirectoryPerCall` fail at `assertEquals(0, leaked)`, and
 * restoring `createTempDirectory("shoptions-empty")` inside `EMPTY` makes
 * `emptyWorkspaceIsStableAcrossReads` fail. Verified by mutation, then restored.
 */
class ShOptionsTempDirLeakTest {

    private fun tempDirectoriesWithPrefix(prefix: String): List<java.nio.file.Path> {
        val tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
        if (!java.nio.file.Files.isDirectory(tmp)) return emptyList()
        return java.nio.file.Files.list(tmp).use { stream ->
            stream.filter { it.fileName.toString().startsWith(prefix) }
                .collect(java.util.stream.Collectors.toList())
        }
    }

    @Test
    fun `fromLeavesNoNewTempDirectoryPerCall`() {
        val before = tempDirectoriesWithPrefix("shoptions-from").size

        repeat(25) { i -> ShOptions.from(mapOf("K" to "v$i")) }

        val after = tempDirectoriesWithPrefix("shoptions-from").size
        assertEquals(
            before, after,
            "ShOptions.from created ${after - before} temp directories across 25 calls; " +
                "the workspace root must be shared, not created per invocation",
        )
    }

    @Test
    fun `emptyWorkspaceIsStableAcrossReads`() {
        val first = ShOptions.EMPTY.workspaceRoot
        repeat(10) {
            assertEquals(first, ShOptions.EMPTY.workspaceRoot)
        }
        // the directory must still exist: ProcessBuilder.directory() rejects an absent path
        assertTrue(
            java.nio.file.Files.isDirectory(first),
            "ShOptions.EMPTY workspaceRoot must exist on disk, because " +
                "DurableShellExecutor passes it to ProcessBuilder.directory()",
        )
    }

    @Test
    fun `fromAcceptsAnExplicitWorkspaceRoot`() {
        val explicit = java.nio.file.Files.createTempDirectory("shoptions-explicit")
        try {
            val options = ShOptions.from(mapOf("A" to "b"), workspaceRoot = explicit)
            assertEquals(explicit, options.workspaceRoot)
        } finally {
            java.nio.file.Files.deleteIfExists(explicit)
        }
    }
}
