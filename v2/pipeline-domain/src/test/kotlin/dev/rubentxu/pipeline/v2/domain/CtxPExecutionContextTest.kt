package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HF0 pure laws for [ExecutionContext] (CTX-P1). No coroutines, no sleeps,
 * no scheduler identity. These laws are the contract the CTX-P2 coordinator
 * threading must preserve and the CTX-P3 race-proof pair relies on.
 */
class CtxPExecutionContextTest {

    private val parent = ExecutionContext(
        listOf(
            ContextOverlay.Cwd("/ws"),
            ContextOverlay.CatchErrorOverlay(
                buildResult = "FAILURE",
                stageResult = "FAILURE",
                message = "outer",
                enteredAt = 1L,
            ),
        ),
    )

    // CTX-Parent: derivation preserves the receiver.
    @Test
    fun ctx1_parent_pushed_is_unchanged() {
        val child = parent.pushed(ContextOverlay.Cwd("/ws/sub"))
        assertEquals(listOf<ContextOverlay>(ContextOverlay.Cwd("/ws"), parent.overlays[1]), parent.overlays)
        assertEquals(3, child.overlays.size)
        // CTX-Nesting: returning from the child needs no restore — parent still IS parent.
        assertEquals(parent, ExecutionContext(parent.overlays))
    }

    // CTX-Siblings: two derivations from one parent never alias.
    @Test
    fun ctx2_sibling_derivations_are_independent() {
        val a = parent.pushed(ContextOverlay.Cwd("/ws/a"))
        val b = parent.pushed(ContextOverlay.Cwd("/ws/b"))
        assertEquals(parent.overlays, a.overlays.dropLast(1))
        assertEquals(parent.overlays, b.overlays.dropLast(1))
        assertFalse(a == b)
        assertEquals("/ws/a", (a.overlays.last() as ContextOverlay.Cwd).path)
        assertEquals("/ws/b", (b.overlays.last() as ContextOverlay.Cwd).path)
        // Parent did not observe either derivation.
        assertEquals(2, parent.overlays.size)
    }

    // CTX-Nesting: child and grandchild chain without restore ops.
    @Test
    fun ctx3_nesting_needs_no_restore() {
        val child = parent.pushed(ContextOverlay.Environment(EnvironmentSpec(mapOf("K" to "V"))))
        val grandchild = child.pushed(ContextOverlay.Cwd("/ws/deep"))
        assertEquals(4, grandchild.overlays.size)
        assertTrue(grandchild.overlays.take(child.overlays.size) == child.overlays)
        assertTrue(child.overlays.take(parent.overlays.size) == parent.overlays)
        // All three values coexist; no pop/restore was ever required.
        assertEquals(3, child.overlays.size)
        assertEquals(2, parent.overlays.size)
    }

    // CTX-CatchError: deterministic pure query, EM-5/6 trailing-chain precedence.
    @Test
    fun ctx4_trailingCatchErrorChain_is_pure_and_ordered() {
        val outer = ContextOverlay.CatchErrorOverlay("FAILURE", "FAILURE", "outer", 1L)
        val inner = ContextOverlay.CatchErrorOverlay("SUCCESS", "FAILURE", "inner", 2L)
        // Trailing chain: Cwd, Catch(outer), Catch(inner) -> [outer, inner] outermost-first.
        val withCatch = ExecutionContext(listOf(ContextOverlay.Cwd("/ws"), outer, inner))
        val chain = withCatch.trailingCatchErrorChain()
        assertEquals(listOf(outer, inner), chain)

        // A non-catch frame between scopes breaks the trailing chain (current walk semantics).
        val separated = parent.pushed(ContextOverlay.Cwd("/ws/x")).pushed(inner)
        assertEquals(listOf(inner), separated.trailingCatchErrorChain())

        val env = ContextOverlay.Environment(EnvironmentSpec(emptyMap()))
        val blocked = withCatch.pushed(env)
        assertEquals(emptyList<ContextOverlay.CatchErrorOverlay>(), blocked.trailingCatchErrorChain())

        // No catch frames at all -> empty chain (abort path unchanged).
        assertTrue(ExecutionContext(listOf(ContextOverlay.Cwd("/"))).trailingCatchErrorChain().isEmpty())

        // Query is repeatable and mutation-free.
        assertEquals(chain, withCatch.trailingCatchErrorChain())
        assertEquals(3, withCatch.overlays.size)
    }

    // CTX-Replay: same logical inputs -> equal value; no scheduling identity inside.
    @Test
    fun ctx5_same_derivations_are_equal_and_identity_free() {
        fun derive(p: ExecutionContext, branch: String) = p.pushed(ContextOverlay.Cwd("/ws/$branch"))
        val replayA = derive(parent, "b0")
        val replayB = derive(parent, "b0")
        assertEquals(replayA, replayB)
        assertEquals(replayA.hashCode(), replayB.hashCode())
        // Value contains only overlays — no thread/coroutine/job identity fields exist.
        assertTrue(replayA.overlays.all { it is ContextOverlay })
    }

    // Structural unwind: dropping exactly the pushed frames returns to the parent value.
    @Test
    fun ctx6_unwind_returns_exactly_to_parent_value() {
        val child = parent.pushed(ContextOverlay.Cwd("/s")).pushed(ContextOverlay.Cwd("/s/d"))
        val unwound = ExecutionContext(child.overlays.dropLast(2))
        assertEquals(parent, unwound)
    }

    // Empty base is total: pushes/drops/queries never throw.
    @Test
    fun ctx7_empty_context_operations_are_total() {
        assertEquals(ExecutionContext(listOf(ContextOverlay.Cwd("/x"))), ExecutionContext.EMPTY.pushed(ContextOverlay.Cwd("/x")))
        assertTrue(ExecutionContext.EMPTY.trailingCatchErrorChain().isEmpty())
    }
}
