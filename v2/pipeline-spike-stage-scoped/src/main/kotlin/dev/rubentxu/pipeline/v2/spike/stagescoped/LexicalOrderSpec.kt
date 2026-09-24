package dev.rubentxu.pipeline.v2.spike.stagescoped

/**
 * Pure invariants for the lexical order of a stage's operations.
 *
 * The stage-scoped surface mixes eager additions and suspend calls. Two
 * questions matter:
 *
 *  1. **Monotonicity.** Within a stage, every [StageOp.Suspend.ordinal] is
 *     unique and monotonically increasing as the source moves forward.
 *     This is the durable identity a replay must preserve.
 *  2. **Eager interleaving.** A [StageOp.Eager] sits at a specific lexical
 *     position between suspend calls; that position is preserved by the
 *     builder. The validator only confirms the [StageOp.Suspend.ordinal]
 *     monotonicity — the eager interleaving is enforced by the *builder*
 *     (pure function over a token stream), not by post-hoc validation.
 *
 * Total: returns a sealed [Result] so callers can branch on `Invalid` and
 * reject before any effect is launched (fail-closed).
 */
object LexicalOrderSpec {

    /** Valid outcome. */
    sealed interface Result {
        /** All suspend ordinals are strictly monotonic. */
        data object Valid : Result

        /**
         * Plan is invalid. [reason] is a typed payload; consumers must
         * pattern-match and reject.
         */
        data class Invalid(val reason: Reason) : Result
    }

    /** Closed set of reasons a plan can be rejected. */
    sealed interface Reason {
        /** First ordinal must be >= 1 (0 is reserved as "no ordinal"). */
        data object OrdinalMustStartAtOne : Reason
        /** Successive suspend ordinals must strictly increase by 1. */
        data class OrdinalGap(val expected: Int, val actual: Int) : Reason
        /** Two suspend calls share the same ordinal. */
        data class DuplicateOrdinal(val ordinal: Int) : Reason
    }

    /**
     * Validate the suspend-ordinal invariants of a list of [StageOp].
     * Pure, total, and side-effect free.
     */
    fun check(ops: List<StageOp>): Result {
        var expected = 1
        val seen = HashSet<Int>()
        for (op in ops) {
            if (op is StageOp.Suspend) {
                if (!seen.add(op.ordinal)) {
                    return Result.Invalid(Reason.DuplicateOrdinal(op.ordinal))
                }
                if (expected == 1 && op.ordinal != 1) {
                    return Result.Invalid(Reason.OrdinalMustStartAtOne)
                }
                if (op.ordinal != expected) {
                    return Result.Invalid(Reason.OrdinalGap(expected, op.ordinal))
                }
                expected += 1
            }
        }
        return Result.Valid
    }
}
