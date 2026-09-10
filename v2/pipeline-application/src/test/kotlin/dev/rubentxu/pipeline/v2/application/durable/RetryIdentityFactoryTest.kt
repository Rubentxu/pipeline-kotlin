package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RED-first tests for [RetryIdentityFactory].
 *
 * ADR-0075 §2 — the canonical retry identity MUST be stable across attempts,
 * deterministic across restarts, and never collide with any child identity.
 *
 * ## Under test
 * - Control opId is the same for all attempts of one retry.
 * - Child opIds are unique per (attempt, childIndex, pluginStepId) tuple.
 * - Distinct retries (different parentBodyPath) produce distinct control opIds.
 * - Distinct runs (different runId) produce distinct control opIds.
 */
class RetryIdentityFactoryTest {

    private val pluginShell = PluginStepId("sh")
    private val pluginEcho = PluginStepId("echo")

    private val parentPath = listOf(BlockSegment(0, PluginStepId("dir")))
    private val parentPathA = listOf(BlockSegment(0, PluginStepId("dir-a")))
    private val parentPathB = listOf(BlockSegment(0, PluginStepId("dir-b")), BlockSegment(2, PluginStepId("withEnv")))

    private fun controlFor(runId: String, path: List<BlockSegment>): String =
        RetryIdentityFactory.controlOperationId(runId, 0, 1, path)

    private fun childFor(runId: String, path: List<BlockSegment>, attempt: Int, childIndex: Int, plugin: PluginStepId): String =
        RetryIdentityFactory.childOperationId(runId, 0, 1, path, attempt, childIndex, plugin)

    @Test
    fun `control opId is stable across attempts`() {
        val c = controlFor("run-x", parentPath)
        val c2 = controlFor("run-x", parentPath)
        assertEquals(c, c2, "control opId must be deterministic across calls")
    }

    @Test
    fun `control opId does not include attempt information`() {
        // Two reads that simulate the same retry on different attempts must produce the same control opId.
        val canonical = RetryIdentityFactory.controlOperationId("run-x", 0, 1, parentPath)
        val reader1 = RetryIdentityFactory.controlOperationId("run-x", 0, 1, parentPath)
        val reader2 = RetryIdentityFactory.controlOperationId("run-x", 0, 1, parentPath)
        assertEquals(canonical, reader1)
        assertEquals(canonical, reader2)
    }

    @Test
    fun `control opId differs across different parent paths`() {
        val c1 = controlFor("run-x", parentPathA)
        val c2 = controlFor("run-x", parentPathB)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `control opId differs across different runIds`() {
        val c1 = controlFor("run-x", parentPath)
        val c2 = controlFor("run-y", parentPath)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `control opId differs across different stage or step indices`() {
        val base = RetryIdentityFactory.controlOperationId("run-x", 0, 1, parentPath)
        val otherStage = RetryIdentityFactory.controlOperationId("run-x", 1, 1, parentPath)
        val otherStep = RetryIdentityFactory.controlOperationId("run-x", 0, 2, parentPath)
        assertNotEquals(base, otherStage)
        assertNotEquals(base, otherStep)
    }

    @Test
    fun `child opIds are unique per attempt`() {
        val c1 = childFor("run-x", parentPath, attempt = 1, childIndex = 0, pluginShell)
        val c2 = childFor("run-x", parentPath, attempt = 2, childIndex = 0, pluginShell)
        assertNotEquals(c1, c2, "attempt 1 and attempt 2 children MUST NOT share an opId")
    }

    @Test
    fun `child opIds are unique per childIndex`() {
        val c1 = childFor("run-x", parentPath, attempt = 1, childIndex = 0, pluginShell)
        val c2 = childFor("run-x", parentPath, attempt = 1, childIndex = 1, pluginShell)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `child opIds are unique per pluginStepId`() {
        val c1 = childFor("run-x", parentPath, attempt = 1, childIndex = 0, pluginShell)
        val c2 = childFor("run-x", parentPath, attempt = 1, childIndex = 0, pluginEcho)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `child opIds are stable for the same triple`() {
        val c1 = childFor("run-x", parentPath, attempt = 1, childIndex = 0, pluginShell)
        val c2 = childFor("run-x", parentPath, attempt = 1, childIndex = 0, pluginShell)
        assertEquals(c1, c2)
    }

    @Test
    fun `control opId never collides with any child opId`() {
        // For a single retry, the control opId MUST NEVER equal any child opId.
        // Otherwise the journal's (opId, attempt) lookup will confuse them.
        val control = controlFor("run-x", parentPath)
        val attempts = (1..5)
        val childPlugins = listOf(pluginShell, pluginEcho)
        for (attempt in attempts) {
            for (childIndex in 0..3) {
                for (plugin in childPlugins) {
                    val child = childFor("run-x", parentPath, attempt, childIndex, plugin)
                    assertTrue(
                        control != child,
                        "control opId MUST NOT collide with attempt=$attempt childIndex=$childIndex plugin=${plugin.value}",
                    )
                }
            }
        }
    }

    @Test
    fun `control opId format is preserved for the canonical empty-parent case`() {
        // Sanity: a retry sitting at the root (no parent segments) still produces a stable opId.
        val c = RetryIdentityFactory.controlOperationId("run-x", 0, 1, emptyList())
        // It must NOT contain any of the per-attempt markers that would indicate child paths.
        assertTrue(!c.contains("retry-attempt"), "control opId MUST NOT contain the retry-attempt marker: $c")
    }
}
