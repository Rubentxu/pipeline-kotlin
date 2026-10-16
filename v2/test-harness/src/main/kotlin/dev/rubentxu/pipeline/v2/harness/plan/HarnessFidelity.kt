package dev.rubentxu.pipeline.v2.harness.plan

/**
 * Closed ADT of execution-boundary fidelity, lifted from ADR-0072.
 *
 * The level is observed by the runner, not inferred from the test name. HF0 is
 * the cheapest (pure contract test, no I/O); HF6 is the heaviest (real
 * distribution against a consumer-facing surface).
 *
 * A `HarnessFidelity` is total over the six cases; adding a level requires an
 * ADR and a runner implementation, not a numeric bump.
 */
enum class HarnessFidelity {
    HF0,
    HF1,
    HF2,
    HF3,
    HF4,
    HF5,
    HF6;

    /** True when this level reaches at least [target] (HF0 ≤ HF1 ≤ … ≤ HF6). */
    fun reaches(target: HarnessFidelity): Boolean = this.ordinal >= target.ordinal
}
