package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `retry(n, conditions = ...) { }` is accepted DSL surface whose failure
 * conditions are NOT implemented anywhere in the compiled execution path:
 *
 *  - `DslCompiledPipelineCompiler` serializes the retry payload with
 *    `maxAttempts` only; `conditions` is never projected into the IR.
 *  - `BlockStepFlattener` walks the nested steps and drops the conditions.
 *  - No production code reads `conditions` off a `RetryBlock`, nor off the
 *    `ContextOverlay.RetryOverlay` variant, which is itself never constructed.
 *
 * A script that narrows "retry only on SCRIPT_FAILURE" and in fact retries on
 * EVERY failure is stating something the engine does not do. And a retry loop
 * that fans out on a non-retryable failure can amplify an outage. The same law
 * that `PostDslFailClosedTest` applies to `post { }`: a declared capability that
 * would be silently dropped MUST be fail-closed, never a fake fallback.
 *
 * The honest path is preserved: `retry(n) { }` with no conditions is real
 * behavior and must keep compiling.
 */
class RetryConditionsFailClosedTest {

    @Test
    fun `retry with conditions is rejected fail-closed with a localized diagnostic`() {
        val ex = assertThrows<IllegalArgumentException> {
            StageScope("build").retry(3, listOf("SCRIPT_FAILURE")) { echo("x") }
        }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("conditions") && msg.contains("SCRIPT_FAILURE") && msg.contains("not supported"),
            "diagnostic must name 'conditions', echo the offending value and state " +
                "non-support, got: $msg",
        )
    }

    @Test
    fun `retry with a blank condition is also rejected rather than silently ignored`() {
        assertThrows<IllegalArgumentException> {
            StageScope("build").retry(3, listOf("  ")) { echo("x") }
        }
    }

    @Test
    fun `retry with an empty condition list is rejected too - it narrows nothing`() {
        assertThrows<IllegalArgumentException> {
            StageScope("build").retry(3, emptyList()) { echo("x") }
        }
    }

    @Test
    fun `retry with no conditions is real behavior and must keep working`() {
        val scope = StageScope("build")
        scope.retry(3) { echo("x") }

        val block = scope.steps().single() as StepSpec.RetryBlock
        assertEquals(3, block.count)
        assertNull(block.conditions, "no conditions must be stored as null, not an empty list")
    }

    @Test
    fun `the escape hatch cannot be reached by passing a null conditions list explicitly`() {
        // A caller who wants the honest path writes retry(n) { }. Passing null
        // explicitly is the same thing, so it must not be treated as a way to
        // smuggle a rejected narrowing past the guard.
        val scope = StageScope("build")
        scope.retry(3, null) { echo("x") }
        assertEquals(1, scope.steps().size, "explicit null is the honest path, not a bypass")
    }
}
