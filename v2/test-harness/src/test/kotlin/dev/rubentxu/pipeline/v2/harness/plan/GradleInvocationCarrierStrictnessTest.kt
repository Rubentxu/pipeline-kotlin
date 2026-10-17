package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.plan.Dispatch.GradleInvocation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * BQ4 v13 (plan carrier self-protect, 2026-10-17): the
 * previous shape of [GradleInvocation] had no init
 * validation. A blank `gradleProject` or `task` would
 * produce a malformed argv (`:test` for an empty
 * project, or `project:` for an empty task) that
 * Gradle silently mis-resolves (the error names the
 * project instead of the missing task, so it reads
 * like a misconfigured module rather than a malformed
 * argv). A blank filter would produce an empty
 * `--tests` argument that Gradle accepts and runs
 * every test.
 *
 * The carrier now refuses all three at construction
 * with precise messages naming the field.
 */
class GradleInvocationCarrierStrictnessTest {

    @Test
    fun `GradleInvocation refuses a blank gradleProject at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            GradleInvocation(
                gradleProject = "",
                task = "test",
                filters = listOf("FooTest"),
            )
        }
        assertTrue(
            (ex.message ?: "").contains("gradleProject must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `GradleInvocation refuses a blank task at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            GradleInvocation(
                gradleProject = ":pipeline-application",
                task = "",
                filters = listOf("FooTest"),
            )
        }
        assertTrue(
            (ex.message ?: "").contains("task must not be blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }

    @Test
    fun `GradleInvocation refuses a blank filter entry at construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            GradleInvocation(
                gradleProject = ":pipeline-application",
                task = "test",
                filters = listOf("FooTest", ""),
            )
        }
        assertTrue(
            (ex.message ?: "").contains("filters must not contain blank"),
            "refusal must name the field; got ${ex.message}",
        )
    }
}
