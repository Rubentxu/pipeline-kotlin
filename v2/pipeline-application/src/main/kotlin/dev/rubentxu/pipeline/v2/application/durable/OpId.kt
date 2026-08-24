package dev.rubentxu.pipeline.v2.application.durable

/**
 * Typed operation identifier. Format: "$runId-s$stageIndex-$stepIndex[-b$branchIndex]".
 *
 * Replaces the string-templated `$runId-s$stageIndex-$stepIndex` hidden contract
 * in [dev.rubentxu.pipeline.v2.application.PipelineRun] (F01 HIGH finding from M3-R3 debt-report).
 *
 * The format is designed to be lexicographically sortable by runId, stage, then step,
 * which enables efficient range queries on the journal.
 *
 * When branchIndex is non-null, the format extends to: "$runId-s$stageIndex-$stepIndex-b$branchIndex"
 *
 * @param runId The pipeline run identifier.
 * @param stageIndex The 0-based stage index within the pipeline.
 * @param stepIndex The 0-based step index within the stage.
 * @param branchIndex The optional 0-based branch index for parallel frame execution.
 */
data class OpId(
    val runId: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val branchIndex: Int? = null,
) {
    /**
     * Formats this OpId into the canonical string representation.
     * @return String in format "$runId-s$stageIndex-$stepIndex[-b$branchIndex]"
     */
    fun format(): String = if (branchIndex != null) {
        "$runId-s$stageIndex-$stepIndex-b$branchIndex"
    } else {
        "$runId-s$stageIndex-$stepIndex"
    }

    /**
     * Returns the string representation (same as format).
     */
    override fun toString(): String = format()

    companion object {
        /**
         * Pattern for OpId without branch: "runId-s{stageIndex}-{stepIndex}"
         */
        private val ROOT_PATTERN = Regex("^(.+)-s(\\d+)-(\\d+)$")

        /**
         * Pattern for OpId with optional branch: "runId-s{stageIndex}-{stepIndex}[-b{branchIndex}]"
         * The branchIndex group is captured as an optional group (may be null).
         */
        private val BRANCH_PATTERN = Regex("^(.+)-s(\\d+)-(\\d+)(-b(\\d+))?$")

        /**
         * Parses a string representation into an [OpId].
         *
         * @param s The string to parse, expected in format "$runId-s$stageIndex-$stepIndex[-b$branchIndex]".
         * @return An [OpId] if parsing succeeds, or `null` if the format is invalid.
         */
        fun parse(s: String): OpId? {
            // First try branch-aware pattern (more general)
            val branchMatch = BRANCH_PATTERN.matchEntire(s) ?: return null
            val (runId, sIdx, stepIdx, _, branchIdx) = branchMatch.destructured
            return OpId(
                runId,
                sIdx.toInt(),
                stepIdx.toInt(),
                branchIdx.takeIf { it.isNotEmpty() }?.toInt()
            )
        }

        /**
         * Constructs an [OpId] for a branch-scoped operation.
         *
         * @param runId The pipeline run identifier.
         * @param stageIndex The 0-based stage index within the pipeline.
         * @param stepIndex The 0-based step index within the stage.
         * @param branchIndex The 0-based branch index for parallel frame execution.
         * @return An [OpId] with branchIndex set.
         */
        fun forBranch(runId: String, stageIndex: Int, stepIndex: Int, branchIndex: Int): OpId =
            OpId(runId, stageIndex, stepIndex, branchIndex)
    }
}
