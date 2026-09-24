package dev.rubentxu.pipeline.v2.spike.stagescoped

/**
 * A closed plan for a single stage. Pure data.
 *
 * Construction is the job of [StagePlanBuilder]; validation is the job of
 * [LexicalOrderSpec.check]; interpretation is the job of the spike frontend.
 *
 * This is intentionally not a `Map<String, Any?>`: every shape is encoded.
 */
data class StagePlan(
    val ops: List<StageOp>,
) {
    init {
        // Fail-closed: every plan must validate before it leaves the builder.
        // The builder is the only constructor allowed to bypass this check
        // (it accumulates ops incrementally and validates once at the end).
        require(ops.isNotEmpty()) { "stage plan must contain at least one op" }
    }

    /** Pure projection: how many suspend calls does this stage plan? */
    val suspendCount: Int
        get() = ops.count { it is StageOp.Suspend }
}
