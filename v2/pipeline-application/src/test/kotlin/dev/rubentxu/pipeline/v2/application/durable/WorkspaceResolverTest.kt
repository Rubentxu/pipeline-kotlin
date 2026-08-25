package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceResolverTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createResolver() = WorkspaceResolver(tempDir)

    @Test
    fun `resolve returns deterministic path`() {
        val resolver = createResolver()
        val path = resolver.resolve("Build", 0)
        assertEquals(tempDir.resolve("workspace").resolve("Build-0"), path)
    }

    @Test
    fun `resolve uses stageIndex for disambiguation`() {
        val resolver = createResolver()
        val path1 = resolver.resolve("Build", 0)
        val path2 = resolver.resolve("Build", 1)
        assertNotEquals(path1, path2)
    }

    @Test
    fun `parallel stages with same name get different paths`() {
        // This verifies WS-S-002: collision-safe naming
        val resolver = createResolver()
        val pathA = resolver.resolve("Deploy", 0)
        val pathB = resolver.resolve("Deploy", 1)
        val pathC = resolver.resolve("Deploy", 2)
        assertNotEquals(pathA, pathB)
        assertNotEquals(pathB, pathC)
        assertNotEquals(pathA, pathC)
    }

    @Test
    fun `resolve escapes special characters in stage name`() {
        val resolver = createResolver()
        val path = resolver.resolve("build/test", 0)
        assertEquals(tempDir.resolve("workspace").resolve("build_test-0"), path)
    }

    @Test
    fun `ensureCreated creates directory`() {
        val resolver = createResolver()
        val path = resolver.resolve("CreateTest", 0)
        assertFalse(Files.exists(path))
        val result = resolver.ensureCreated(path)
        assertEquals(path, result)
        assertTrue(Files.exists(path))
        assertTrue(Files.isDirectory(path))
    }

    @Test
    fun `ensureCreated is idempotent`() {
        val resolver = createResolver()
        val path = resolver.resolve("IdempotentTest", 0)
        resolver.ensureCreated(path)
        // Second call should not throw
        val result = resolver.ensureCreated(path)
        assertEquals(path, result)
        assertTrue(Files.exists(path))
    }

    @Test
    fun `cleanupAfterComplete removes directory`() {
        val resolver = createResolver()
        val path = resolver.resolve("CleanupTest", 0)
        resolver.ensureCreated(path)
        assertTrue(Files.exists(path))
        resolver.cleanupAfterComplete(path)
        assertFalse(Files.exists(path))
    }

    @Test
    fun `cleanupAfterComplete is idempotent on missing directory`() {
        val resolver = createResolver()
        val path = resolver.resolve("MissingCleanup", 0)
        // Should not throw
        resolver.cleanupAfterComplete(path)
        assertFalse(Files.exists(path))
    }

    @Test
    fun `retainOnFailure does not throw on missing directory`() {
        val resolver = createResolver()
        val path = resolver.resolve("RetainMissing", 0)
        // Should not throw - this is a no-op
        resolver.retainOnFailure(path)
        assertFalse(Files.exists(path))
    }

    @Test
    fun `retainOnFailure does not delete existing directory`() {
        val resolver = createResolver()
        val path = resolver.resolve("RetainExisting", 0)
        resolver.ensureCreated(path)
        assertTrue(Files.exists(path))
        // Should not delete - it's retained on failure
        resolver.retainOnFailure(path)
        assertTrue(Files.exists(path))
    }
}
