package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `scmGit` / `git` emission contract, part of the Semantic Conservation Law
 * (TRAIN-DSL-HONESTY).
 *
 * Before this test the only coverage was `CheckoutDslTest`, whose case named
 * "git shorthand desugars to checkout with scmGit" asserted nothing but the
 * absence of a compile error. The duplication it missed:
 *
 * ```kotlin
 * fun scmGit(...): CheckoutSpec {
 *     val spec = CheckoutSpec(GitScm(...))
 *     steps.add(StepSpec.Checkout(spec.scm))   // side effect
 *     return spec
 * }
 * fun git(...) { checkout(scmGit(...).scm) }  // adds a SECOND Checkout
 * ```
 *
 * so `git(...)` emitted two `StepSpec.Checkout` rows for one checkout.
 *
 * OBSERVED as duplicate execution intent, not inferred: each `Checkout` is a
 * canonical step, so the `non-canonical plugins` bridge gate is green and proves
 * nothing here. This is the same evidence gap that let `whenCondition` ship a
 * documented-but-false fail-closed.
 *
 * The law applied: `scmGit` is a PURE_CONSTRUCTOR (returns the spec, emits
 * nothing), and `git` is PURE_DESUGAR to exactly one `checkout`.
 */
class ScmGitEmissionContractTest {

    private fun checkouts(scope: StageScope) = scope.steps().filter { it is StepSpec.Checkout }

    @Test
    fun `scmGit is a pure constructor and emits no step`() {
        val scope = StageScope("Test")
        val spec = scope.scmGit("https://github.com/example/repo.git")

        assertEquals(CheckoutSpec::class, spec::class)
        assertEquals(
            0,
            scope.steps().size,
            "scmGit is a PURE_CONSTRUCTOR: it must not append to steps, got ${scope.steps()}",
        )
    }

    @Test
    fun `checkout of an scmGit spec emits exactly one checkout`() {
        val scope = StageScope("Test")
        val spec = scope.scmGit("https://github.com/example/repo.git", relativeTargetDir = "src")
        scope.checkout(spec.scm)

        val found = checkouts(scope)
        assertEquals(1, found.size, "checkout(scmGit(..)) must emit exactly one Checkout, got $found")
        val scm = (found[0] as StepSpec.Checkout).scm as GitScm
        assertEquals("src", scm.relativeTargetDir, "relativeTargetDir must survive to the emitted step")
    }

    @Test
    fun `git shorthand emits exactly one checkout`() {
        val scope = StageScope("Test")
        scope.git("https://github.com/example/repo.git", "main", null, true, true)

        val found = checkouts(scope)
        assertEquals(1, found.size, "git(..) must emit exactly one Checkout, got $found")
    }

    @Test
    fun `git preserves every parameter it accepts`() {
        val scope = StageScope("Test")
        val credId = CredentialsId("my-creds")
        scope.git("https://github.com/example/repo.git", "develop", credId, false, false)

        val found = checkouts(scope)
        assertEquals(1, found.size, "git(..) must emit exactly one Checkout, got $found")
        val scm = (found[0] as StepSpec.Checkout).scm as GitScm
        assertEquals("https://github.com/example/repo.git", scm.url)
        assertEquals("develop", scm.branch)
        assertEquals(credId, scm.credentialsId)
        assertEquals(false, scm.changelog)
        assertEquals(false, scm.poll)
    }

    @Test
    fun `three git calls emit three checkouts not six`() {
        // The regression's real-world shape: duplicated construction is
        // per-call, so it scales with usage rather than showing up once.
        val scope = StageScope("Test")
        repeat(3) { scope.git("https://github.com/example/repo.git") }

        assertEquals(3, checkouts(scope).size, "one Checkout per git() call, got ${checkouts(scope)}")
    }

    @Test
    fun `git rejects a blank url rather than emitting a checkout`() {
        val scope = StageScope("Test")
        val ex = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            scope.git("   ")
        }
        assertTrue(ex.message?.contains("url") == true, "diagnostic must name the offending parameter, got ${ex.message}")
        assertEquals(0, scope.steps().size, "a rejected git() must not emit a step, got ${scope.steps()}")
    }
}
