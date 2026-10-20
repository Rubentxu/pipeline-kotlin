package dev.rubentxu.pipeline.v2.dsl

/**
 * S3-R1-B — the ONE bound a stage timeout must satisfy, stated once.
 *
 * ## Why this object exists
 *
 * A stage `options { timeout(n) }` is a duration in SECONDS that becomes a duration in
 * MILLISECONDS, and both must fit a `Long`. That is a single fact about the option, and
 * until S3-R1-B it was stated in two places that did not agree:
 *
 * ```
 * OptionsScope.timeout(seconds)   require(seconds > 0)     // positivity only
 * OptionsSpec(timeout = …)        no validation at all    // the public model
 * ```
 *
 * so `timeout(Long.MAX_VALUE)` passed both doors and detonated later as
 * `Math.multiplyExact: long overflow` inside the compiler. The drift was not a
 * transcription error; it was two authorities for one rule, which is the shape every
 * duplicated invariant in this repository has taken.
 *
 * ## What the bound buys
 *
 * With [MAX_SECONDS] enforced at every door, the seconds-to-milliseconds conversion in
 * `DslCompiledPipelineCompiler` cannot overflow and `StageOption.Timeout`'s own positivity
 * invariant cannot be violated by a DSL-authored value. `Math.multiplyExact` stays where it
 * is and becomes what it should have been: an assertion that the boundary holds, not a live
 * crash site.
 *
 * `internal` on purpose. Both doors that can admit an unvalidated value live in this
 * module; every other module consumes an already-validated [OptionsSpec], so publishing
 * the constant would add surface without adding a second reader that could drift.
 */
internal object StageTimeout {

    /**
     * The largest whole number of seconds whose millisecond form still fits a `Long`.
     *
     * Inclusive, and the bound is the floor division for that reason:
     * `Long.MAX_VALUE / 1_000L` multiplied by `1_000L` is still inside `Long`, and one
     * more is not.
     */
    const val MAX_SECONDS: Long = Long.MAX_VALUE / 1_000L

    /**
     * Is [seconds] a deadline this runtime can represent?
     *
     * Positive AND convertible. A `require` of each half separately reads as two rules;
     * this is one rule with one reason, and the two halves are inseparable — a positive
     * value that cannot be projected is not a longer deadline, it is a broken one.
     */
    fun isValid(seconds: Long): Boolean = seconds in 1..MAX_SECONDS
}
