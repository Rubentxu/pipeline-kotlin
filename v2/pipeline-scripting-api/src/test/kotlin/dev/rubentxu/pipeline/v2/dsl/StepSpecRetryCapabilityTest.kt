package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Pins the retry SURFACE after the S0 Semantic Honesty Gate removal.
 *
 * The step-retrofit overload `retry(count, delaySeconds)` projected a full
 * RetryPolicy (maxAttempts + baseMs + jitterMs) onto the preceding step, but
 * no runtime consumer ever read that policy: the coordinator consumes only
 * `maxAttempts` from the `core.retry` block payload. A policy without an
 * interpreter is metadata-without-an-interpreter, so the overload was removed
 * (fail closed) and the block form `retry(n) { ... }` is the only supported
 * step-retry surface. These tests pin that contract, including its diagnostics.
 */
class StepSpecRetryCapabilityTest {

    @Test
    fun `the retrofit overload fails closed with a diagnostic pointing at the block form`() {
        val scope = StageScope("build")
        scope.echo("hello")

        val ex = assertThrows<IllegalArgumentException> { scope.retry(count = 3) }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("removed"), "diagnostic must state removal, got: $msg")
        assertTrue(
            msg.contains("no ") && msg.contains("consumer"),
            "diagnostic must state the missing runtime consumer, got: $msg",
        )
        assertTrue(
            msg.contains("retry(") && msg.contains("{"),
            "diagnostic must point at the block form, got: $msg",
        )
        assertEquals(1, scope.steps().size, "a rejected retrofit must not add or change steps")
    }

    @Test
    fun `the retrofit overload fails closed even with delaySeconds`() {
        val scope = StageScope("build")
        scope.echo("hello")

        val ex = assertThrows<IllegalArgumentException> {
            scope.retry(count = 3, delaySeconds = 2)
        }
        assertTrue(
            (ex.message ?: "").contains("delaySeconds"),
            "diagnostic must name delaySeconds as never executed, got: ${ex.message}",
        )
    }

    @Test
    fun `the retrofit overload fails closed with no preceding step`() {
        val scope = StageScope("build")
        assertThrows<IllegalArgumentException> { scope.retry(count = 3) }
        assertTrue(scope.steps().isEmpty(), "retry must not add a step of its own")
    }

    @Test
    fun `StepSpec subtypes carry no step-level retry field`() {
        val echoFields = StepSpec.Echo("x")::class.java.declaredFields.map { it.name }
        assertTrue("retry" !in echoFields, "step-level retry field must be gone from StepSpec: $echoFields")
        assertTrue(
            "timeoutMillis" !in echoFields,
            "phantom timeoutMillis field must be gone from StepSpec: $echoFields",
        )

        val blockFields = StepSpec.RetryBlock(1, null, emptyList())::class.java.declaredFields.map { it.name }
        assertTrue("retry" !in blockFields, "step-level retry field must be gone from RetryBlock: $blockFields")
    }

    @Test
    fun `the block form wraps steps into a RetryBlock`() {
        val scope = StageScope("build")
        scope.retry(count = 2) { echo("inside") }

        val block = scope.steps().single()
        assertTrue(
            block is StepSpec.RetryBlock,
            "expected a RetryBlock, got ${block::class.simpleName}",
        )
    }

    @Test
    fun `the wrapped block keeps its inner steps`() {
        val scope = StageScope("build")
        scope.retry(count = 2) { echo("inside") }

        val block = scope.steps().single() as StepSpec.RetryBlock
        assertEquals(2, block.count)
        assertEquals(1, block.steps.size, "the nested echo must survive wrapping")
    }
}
