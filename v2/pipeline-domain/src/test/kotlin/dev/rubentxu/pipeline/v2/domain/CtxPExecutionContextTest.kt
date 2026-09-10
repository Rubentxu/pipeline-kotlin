package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HF0 pure laws for [ExecutionContext] (CTX-P1 corrected by the P2 equivalence
 * RED + P2 state-machine laws CTX-1..7). No coroutines, no sleeps.
 *
 * CTX-P2-1 innermost-first trailing chain
 * CTX-P2-2 Entered push transition
 * CTX-P2-3 Triggered exit transition
 * CTX-P2-4 Triggered without active catch -> rejected (fail closed)
 * CTX-P2-5 nested lifecycle: [] -> [outer] -> [outer,inner] -> chain [inner,outer] -> [outer] -> []
 * CTX-P2-7 no stale frames after exits (unrelated later failure observes none)
 * plus parent/sibling/nesting/replay value laws.
 */
class CtxPExecutionContextTest {

    private fun catchO(result: String, msg: String) =
        ContextOverlay.CatchErrorOverlay(buildResult = result, stageResult = result, message = msg, enteredAt = 1L)

    // ---- P1 value laws ------------------------------------------------------

    @Test
    fun ctx1_parent_pushed_is_unchanged() {
        val parent = ExecutionContext(listOf(ContextOverlay.Cwd("/ws")))
        val child = parent.pushed(ContextOverlay.Cwd("/ws/sub"))
        assertEquals(listOf<ContextOverlay>(ContextOverlay.Cwd("/ws")), parent.overlays)
        assertEquals(2, child.overlays.size)
    }

    @Test
    fun ctx2_sibling_derivations_are_independent() {
        val parent = ExecutionContext(listOf(ContextOverlay.Cwd("/ws")))
        val a = parent.pushed(ContextOverlay.Cwd("/ws/a"))
        val b = parent.pushed(ContextOverlay.Cwd("/ws/b"))
        assertFalse(a == b)
        assertEquals(parent.overlays, a.overlays.dropLast(1))
        assertEquals(parent.overlays, b.overlays.dropLast(1))
        assertEquals(1, parent.overlays.size)
    }

    @Test
    fun ctx3_nesting_needs_no_restore() {
        val parent = ExecutionContext(listOf(ContextOverlay.Cwd("/ws")))
        val child = parent.pushed(ContextOverlay.Environment(EnvironmentSpec(mapOf("K" to "V"))))
        val grandchild = child.pushed(ContextOverlay.Cwd("/ws/deep"))
        assertTrue(grandchild.overlays.take(child.overlays.size) == child.overlays)
        assertTrue(child.overlays.take(parent.overlays.size) == parent.overlays)
        assertEquals(1, parent.overlays.size)
    }

    @Test
    fun ctx5_same_derivations_are_equal_and_identity_free() {
        val parent = ExecutionContext(listOf(ContextOverlay.Cwd("/ws")))
        fun derive(p: ExecutionContext, branch: String) = p.pushed(ContextOverlay.Cwd("/ws/$branch"))
        assertEquals(derive(parent, "b0"), derive(parent, "b0"))
    }

    // ---- CTX-P2 state-machine laws -----------------------------------------

    // CTX-P2-1: innermost-first trailing chain, cut at first non-catch frame.
    @Test
    fun `P2-1 trailing chain is innermost first and cut at non-catch frames`() {
        val outer = catchO("FAILURE", "outer")
        val inner = catchO("SUCCESS", "inner")
        val withCatch = ExecutionContext(listOf(ContextOverlay.Cwd("/ws"), outer, inner))
        assertEquals(listOf(inner, outer), withCatch.trailingCatchErrorChain())

        val separated = ExecutionContext(listOf(outer, ContextOverlay.Cwd("/ws/x"), inner))
        assertEquals(listOf(inner), separated.trailingCatchErrorChain())

        assertTrue(ExecutionContext(listOf(ContextOverlay.Cwd("/"))).trailingCatchErrorChain().isEmpty())
        // Pure: repeatable, no mutation.
        assertEquals(listOf(inner, outer), withCatch.trailingCatchErrorChain())
        assertEquals(3, withCatch.overlays.size)
    }

    // CTX-P2-2: Entered -> ctx0.pushed(catch) == ctx1.
    @Test
    fun `P2-2 Entered transitions ctx0 to ctx1 by pure push`() {
        val ctx0 = ExecutionContext.EMPTY
        val ctx1 = ctx0.pushed(catchO("UNSTABLE", "c1"))
        assertEquals(1, ctx1.overlays.size)
        assertEquals(0, ctx0.overlays.size)
    }

    // CTX-P2-3: Triggered(emitted=true) on an active scope -> ctx0 back.
    @Test
    fun `P2-3 Triggered exits the active catch scope`() {
        val ctx1 = ExecutionContext.EMPTY.pushed(catchO("UNSTABLE", "c1"))
        val exit = ctx1.exitCatchError()
        val ctx2 = (exit as ContextTransition.Advanced).context
        assertEquals(ExecutionContext.EMPTY, ctx2)
    }

    // CTX-P2-4: Triggered without an active catch scope -> fail-closed rejection.
    @Test
    fun `P2-4 Triggered without active catch is a rejected invariant violation`() {
        val exit = ExecutionContext.EMPTY.exitCatchError()
        assertTrue(exit is ContextTransition.Rejected)
        assertEquals(ContextInvariantViolation.CATCH_ERROR_UNDERFLOW, (exit as ContextTransition.Rejected).violation)

        // Top frame is a non-catch overlay -> also rejected (old peek-pop guard).
        val exit2 = ExecutionContext(listOf(ContextOverlay.Cwd("/ws"))).exitCatchError()
        assertTrue(exit2 is ContextTransition.Rejected)
    }

    // CTX-P2-5: full nested lifecycle fold.
    @Test
    fun `P2-5 nested lifecycle fold`() {
        val outer = catchO("UNSTABLE", "outer")
        val inner = catchO("FAILURE", "inner")
        var ctx = ExecutionContext.EMPTY            // []
        ctx = ctx.pushed(outer)                     // [outer]
        ctx = ctx.pushed(inner)                     // [outer, inner]
        // Failure point: walk observes [inner, outer] (innermost-first).
        assertEquals(listOf(inner, outer), ctx.trailingCatchErrorChain())
        ctx = (ctx.exitCatchError() as ContextTransition.Advanced).context   // inner exit -> [outer]
        assertEquals(listOf(outer), ctx.overlays)
        ctx = (ctx.exitCatchError() as ContextTransition.Advanced).context   // outer exit -> []
        assertEquals(ExecutionContext.EMPTY, ctx)
    }

    // CTX-P2-7: after both exits, an unrelated later failure observes ZERO stale frames.
    @Test
    fun `P2-7 no stale catch frames survive scope exits`() {
        var ctx = ExecutionContext.EMPTY
        ctx = ctx.pushed(catchO("UNSTABLE", "outer"))
        ctx = ctx.pushed(catchO("FAILURE", "inner"))
        ctx = (ctx.exitCatchError() as ContextTransition.Advanced).context
        ctx = (ctx.exitCatchError() as ContextTransition.Advanced).context
        assertTrue(ctx.trailingCatchErrorChain().isEmpty())
        assertEquals(ExecutionContext.EMPTY, ctx)
    }

    // Structural unwind / totality retained from P1.
    @Test
    fun ctx6_unwind_returns_exactly_to_parent_value() {
        val parent = ExecutionContext(listOf(ContextOverlay.Cwd("/ws")))
        val child = parent.pushed(ContextOverlay.Cwd("/s")).pushed(ContextOverlay.Cwd("/s/d"))
        assertEquals(parent, ExecutionContext(child.overlays.dropLast(2)))
    }

    @Test
    fun ctx7_empty_context_operations_are_total() {
        assertEquals(ExecutionContext(listOf(ContextOverlay.Cwd("/x"))), ExecutionContext.EMPTY.pushed(ContextOverlay.Cwd("/x")))
        assertTrue(ExecutionContext.EMPTY.trailingCatchErrorChain().isEmpty())
    }
}
