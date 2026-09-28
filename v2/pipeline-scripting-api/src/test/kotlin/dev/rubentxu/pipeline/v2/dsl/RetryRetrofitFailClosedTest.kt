package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `retry(count, delaySeconds)` — the step-retrofit overload — used to swallow an
 * explicit user intent in two ways:
 *
 * ```kotlin
 * val currentStep = steps.lastOrNull() ?: return          // (a) silent no-op
 * steps[index] = if (currentStep.supportsStepLevelRetry)
 *     currentStep.withRetry(retryPolicy)
 * else
 *     currentStep                                       // (b) policy dropped
 * ```
 *
 * Both branches produced a green build. `StepSpecRetryCapabilityTest` went
 * further and *certified* the silence as the contract, with cases named
 * "retry before any step is a no-op" and "a non-retryable step is left
 * untouched by retry".
 *
 * Under the Semantic Conservation Law (TRAIN-DSL-HONESTY) that is a
 * `MUTATE_IF_POSSIBLE_ELSE_IGNORE` shape: the caller declared retry semantics,
 * and the DSL decided they did not matter. Same class as `whenCondition`
 * discarding its predicate and `scmGit` emitting twice — only here the two
 * failures look identical from the outside, because a dropped policy and an
 * applied policy both leave a valid-looking `StageScope` behind.
 *
 * Fail-closed is the honest outcome. The block form `retry(n) { ... }` remains
 * the supported way to express retry for steps that cannot carry a policy, and
 * the diagnostics say so.
 *
 * The happy path is unchanged and stays covered by `StepSpecRetryCapabilityTest`:
 * `echo(..)` is retryable and receives the exact policy.
 */
class RetryRetrofitFailClosedTest {

    @Test
    fun `retry with no preceding step is rejected instead of silently doing nothing`() {
        val ex = assertThrows<IllegalArgumentException> {
            StageScope("build").retry(count = 3)
        }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("retry") && msg.contains("step"),
            "diagnostic must explain that the retrofit form needs a preceding step, got: $msg",
        )
    }

    @Test
    fun `retry over a non-retryable step is rejected and suggests the block form`() {
        val scope = StageScope("build")
        scope.writeFile("out.txt", "content")

        val ex = assertThrows<IllegalArgumentException> {
            scope.retry(count = 3)
        }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("WriteFile") || msg.contains("not support"),
            "diagnostic must name the offending step or the missing capability, got: $msg",
        )
        assertTrue(
            msg.contains("retry(") && msg.contains("{"),
            "diagnostic must point at the block form retry(n) { ... }, got: $msg",
        )
    }

    @Test
    fun `a rejected retry leaves the preceding step untouched`() {
        val scope = StageScope("build")
        scope.writeFile("out.txt", "content")
        val before = scope.steps()

        assertThrows<IllegalArgumentException> { scope.retry(count = 3) }

        assertTrue(
            scope.steps() == before,
            "a rejected retry must not mutate the step it refused to configure, got ${scope.steps()}",
        )
        assertTrue(
            scope.steps().single().retry == null,
            "the dropped policy must not leak into the step, got ${scope.steps().single().retry}",
        )
    }

    @Test
    fun `a rejected retry names the step kind that cannot carry a policy`() {
        // Pins the diagnostic quality: "retry failed" is not actionable, the
        // caller needs to know which step refused the policy.
        val scope = StageScope("build")
        scope.writeFile("out.txt", "content")
        val ex = assertThrows<IllegalArgumentException> { scope.retry(count = 3) }
        assertTrue(
            (ex.message ?: "").contains("WriteFile"),
            "diagnostic must name the offending step kind, got: ${ex.message}",
        )
    }
}
