package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * S0 Semantic Honesty Gate: the `conditions` parameter was REMOVED from the
 * retry block form. It used to be accepted and rejected at runtime (fail-closed
 * with a diagnostic), but a declared narrowing that never reaches the IR is a
 * lie about the surface, not just about execution. The surface now only
 * accepts `retry(n) { }` without conditions, so misuses fail with the plain
 * Kotlin signature error (no `retry(count, conditions, block)` overload exists).
 *
 * The honest path is preserved: `retry(n) { }` with no conditions is real
 * behavior and must keep compiling.
 */
class RetryConditionsFailClosedTest {

    @Test
    fun `retry with conditions no longer compiles as an overload`() {
        // The rejected overload is GONE from the surface: retry(3, listOf(..)) { }
        // must not resolve. We pin that by asserting no such overload exists on
        // StageScope via reflection.
        val overloads = StageScope::class.java.methods
            .filter { it.name == "retry" }
            .map { it.parameters.joinToString(",") { p -> p.type.simpleName } }
        assertTrue(
            overloads.all { !it.contains("List") },
            "retry must not accept a conditions list on any overload, got: $overloads",
        )
        assertEquals(
            2,
            overloads.size,
            "exactly the block form and the removed retrofit stub must exist, got: $overloads",
        )
    }

    @Test
    fun `retry with no conditions is real behavior and must keep working`() {
        val scope = StageScope("build")
        scope.retry(3) { echo("x") }

        val block = scope.steps().single() as StepSpec.RetryBlock
        assertEquals(3, block.count)
        assertNull(block.conditions, "no conditions must be stored as null, not an empty list")
    }
}
