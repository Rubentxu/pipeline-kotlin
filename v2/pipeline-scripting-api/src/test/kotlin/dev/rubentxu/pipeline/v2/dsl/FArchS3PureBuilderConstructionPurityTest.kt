package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S3.0 — the construction-time half of the PURE_BUILDER purity law.
 *
 * This file exists because of a defect in its own first version, and that
 * defect is worth stating plainly because it is the kind that survives review.
 *
 * The runtime witness (`FArchS3PureBuilderPurityWitnessTest`, in
 * `pipeline-application`) runs real pipelines through the installed
 * distribution and asserts that a pure builder emits no event and executes no
 * step. Applied as a mutation — `scmGit(..)` calling `echo(..)` before it
 * returns its carrier — that witness stayed GREEN, and it stayed green for a
 * structural reason rather than by luck:
 *
 *  - a DISCARDED carrier fails closed at construction, and
 *  - a CONSUMED carrier fails closed at the canonical bridge, because
 *    `core.checkout` has no plugin registration today.
 *
 * In both cases the run is refused before execution, so an appended step never
 * runs, never succeeds, and never appears. The witness was measuring "no effect
 * was OBSERVED", which a build-time refusal guarantees for a good builder and
 * a bad one alike. It proved nothing about purity; it proved the gate fires.
 *
 * The fix is to observe one layer earlier, where the impurity is still
 * visible: the step list the DSL built. A pure builder that appends a
 * `StepSpec.Echo` is wrong in the data structure itself, whether or not a later
 * gate happens to stop it. That is directly assertable here, with no
 * distribution, no host and no timing.
 *
 * The two layers are complementary and both are kept:
 *  - this one catches impurity in the constructed program (fast, exact);
 *  - the runtime one catches a builder that reaches the effect boundary by some
 *    route this file cannot see (slow, end-to-end).
 */
class FArchS3PureBuilderConstructionPurityTest {

    /**
     * The reference pattern from the law: `scmGit` builds a carrier, `checkout`
     * owns the effect. The stage must therefore contain exactly ONE step — the
     * checkout — and the builder must contribute none of its own.
     */
    @Test
    fun `a consumed pure builder contributes no step of its own`() {
        val scope = StageScope("Test")
        scope.checkout(scope.scmGit("https://example.invalid/r.git").scm)

        val spec = scope.toStageBuilder().build()

        assertEquals(
            1,
            spec.steps.size,
            "a PURE_BUILDER must contribute no StepSpec: the stage should hold exactly the " +
                "checkout that consumed its carrier, but holds ${spec.steps.map { it::class.simpleName }}",
        )
        assertTrue(
            spec.steps.single() is StepSpec.Checkout,
            "the only step must be the checkout that consumed the carrier, but was " +
                "${spec.steps.single()::class.simpleName}",
        )
    }

    /**
     * The control that gives the assertion above its meaning.
     *
     * Without this, "exactly one step" could be satisfied by a DSL whose step
     * plumbing is broken in the other direction and appends nothing at all.
     */
    @Test
    fun `an emitting step is visible in the same step list`() {
        val scope = StageScope("Test")
        scope.echo("hello")

        val spec = scope.toStageBuilder().build()

        assertEquals(
            1,
            spec.steps.size,
            "echo must append exactly one step, so the purity assertion above is measuring a " +
                "list that does react to emission: ${spec.steps.map { it::class.simpleName }}",
        )
        assertTrue(
            spec.steps.single() is StepSpec.Echo,
            "expected an Echo step, got ${spec.steps.single()::class.simpleName}",
        )
    }

    /**
     * The builder must be a builder: it produces a carrier and appends nothing,
     * even when its result is thrown away. The consumption gate will reject the
     * discarded carrier LATER; what matters here is that nothing was appended on
     * the way to that rejection.
     */
    @Test
    fun `a discarded pure builder appends nothing before it is rejected`() {
        val scope = StageScope("Test")
        // Deliberately discarded. `toStageBuilder()` does not run the MUST_CONSUME
        // gate (that fires in the compiler), so this observes the step list
        // without the rejection short-circuiting the observation.
        scope.scmGit("https://example.invalid/r.git")

        val spec = scope.toStageBuilder().build()

        assertEquals(
            emptyList<Any>(),
            spec.steps.toList(),
            "a PURE_BUILDER must append no StepSpec even when its carrier is discarded, " +
                "but appended ${spec.steps.map { it::class.simpleName }}",
        )
    }
}
