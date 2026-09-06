package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for ContextStack immutability and ContextOverlay sealed exhaustiveness.
 */
class ContextStackImmutabilityTest {

    @Test
    fun `ContextStack push returns new instance`() {
        val original = ContextStack.EMPTY
        val pushed = original.push(ContextOverlay.Cwd("/a"))

        assertNotNull(pushed)
        assertNotEquals(original, pushed, "push should return a new instance")
        assertEquals(1, pushed.size)
        assertTrue(pushed.frames !== original.frames, "push should create new frames list")
    }

    @Test
    fun `ContextStack peek returns last overlay`() {
        val stack = ContextStack.EMPTY
            .push(ContextOverlay.Cwd("/a"))
            .push(ContextOverlay.Environment(EnvironmentSpec(mapOf("PATH" to "/usr/bin"))))

        val peeked = stack.peek()
        assertTrue(peeked is ContextOverlay.Environment)
    }

    @Test
    fun `ContextStack pop returns new instance without last`() {
        val stack = ContextStack.EMPTY
            .push(ContextOverlay.Cwd("/a"))
            .push(ContextOverlay.Environment(EnvironmentSpec(mapOf("PATH" to "/usr/bin"))))

        val popped = stack.pop()

        assertNotEquals(stack, popped, "pop should return a new instance")
        assertEquals(1, popped.size)
        assertTrue(popped.peek() is ContextOverlay.Cwd)
    }

    @Test
    fun `ContextStack peek on empty returns null`() {
        val empty = ContextStack.EMPTY
        assertNull(empty.peek())
        assertTrue(empty.isEmpty)
    }

    @Test
    fun `ContextStack isEmpty returns true for empty stack`() {
        assertTrue(ContextStack.EMPTY.isEmpty)
        assertEquals(0, ContextStack.EMPTY.size)
    }

    @Test
    fun `ContextOverlay sealed exhaustiveness covers all variants`() {
        // All 8 variants should be covered (6 standard + 2 block-step overlays: TimeoutOverlay, RetryOverlay)
        val overlays = listOf(
            ContextOverlay.Environment(EnvironmentSpec(mapOf("KEY" to "value"))),
            ContextOverlay.Cwd("/workspace"),
            ContextOverlay.Credentials("secret-id"),
            ContextOverlay.OutputDecorator("ansi"),
            ContextOverlay.CancellationScope("scope-123"),
            ContextOverlay.CatchErrorOverlay("FAILURE", System.currentTimeMillis()),
            ContextOverlay.TimeoutOverlay(30, "MINUTES"),
            ContextOverlay.RetryOverlay(3, listOf("SCRIPT_FAILURE")),
        )

        // Verify each variant can be created and is of the correct type
        overlays.forEach { overlay ->
            assertNotNull(overlay)
            when (overlay) {
                is ContextOverlay.Environment -> assertTrue(overlay.values.values.containsKey("KEY"))
                is ContextOverlay.Cwd -> assertEquals("/workspace", overlay.path)
                is ContextOverlay.Credentials -> assertEquals("secret-id", overlay.bindingId)
                is ContextOverlay.OutputDecorator -> assertEquals("ansi", overlay.kind)
                is ContextOverlay.CancellationScope -> assertEquals("scope-123", overlay.scopeId)
                is ContextOverlay.CatchErrorOverlay -> assertEquals("FAILURE", overlay.buildResult)
                is ContextOverlay.TimeoutOverlay -> { assertEquals(30L, overlay.time); assertEquals("MINUTES", overlay.unit) }
                is ContextOverlay.RetryOverlay -> { assertEquals(3, overlay.count); assertEquals(listOf("SCRIPT_FAILURE"), overlay.conditions) }
            }
        }
    }

    @Test
    fun `ContextStack pop on single element returns empty stack`() {
        val stack = ContextStack.EMPTY.push(ContextOverlay.Cwd("/tmp"))
        val popped = stack.pop()

        assertTrue(popped.isEmpty)
        assertEquals(0, popped.size)
    }

    @Test
    fun `ContextStack multiple pushes and pops maintain order`() {
        var stack = ContextStack.EMPTY
        stack = stack.push(ContextOverlay.Cwd("/a"))
        stack = stack.push(ContextOverlay.Cwd("/b"))
        stack = stack.push(ContextOverlay.Cwd("/c"))

        assertEquals(3, stack.size)
        assertEquals("/c", (stack.peek() as ContextOverlay.Cwd).path)

        stack = stack.pop()
        assertEquals(2, stack.size)
        assertEquals("/b", (stack.peek() as ContextOverlay.Cwd).path)

        stack = stack.pop()
        assertEquals(1, stack.size)
        assertEquals("/a", (stack.peek() as ContextOverlay.Cwd).path)
    }
}
