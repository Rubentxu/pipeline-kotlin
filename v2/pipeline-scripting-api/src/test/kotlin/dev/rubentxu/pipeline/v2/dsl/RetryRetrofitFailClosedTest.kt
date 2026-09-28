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
 * The S0 audit (A3) went one step further: even the "applied" branch was a
 * lie, because NO runtime consumer ever read the projected RetryPolicy. The
 * canonical coordinator consumes only `maxAttempts` from the `core.retry`
 * block payload; `baseMs`/`jitterMs` were never executed by any code path.
 * The overload was therefore REMOVED and now fails closed unconditionally.
 * The block form `retry(n) { ... }` remains the supported surface.
 */
class RetryRetrofitFailClosedTest {

    @Test
    fun `the retrofit overload is removed and fails closed unconditionally`() {
        val scope = StageScope("build")
        scope.echo("hello")
        val ex = assertThrows<IllegalArgumentException> {
            scope.retry(count = 3)
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("removed"), "diagnostic must state removal, got: $msg")
        assertTrue(msg.contains("retry(") && msg.contains("{"), "must point at the block form, got: $msg")
    }

    @Test
    fun `a rejected retrofit leaves the stage steps untouched`() {
        val scope = StageScope("build")
        scope.writeFile("out.txt", "content")
        val before = scope.steps()

        assertThrows<IllegalArgumentException> { scope.retry(count = 3) }

        assertTrue(
            scope.steps() == before,
            "a rejected retry must not mutate the step it refused to configure, got ${scope.steps()}",
        )
    }
}
