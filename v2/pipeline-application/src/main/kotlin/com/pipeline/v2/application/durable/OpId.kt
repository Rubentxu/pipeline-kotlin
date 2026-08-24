package com.pipeline.v2.application.durable

/**
 * Typed operation identifier. Format: "$runId-s$stageIndex-$stepIndex".
 *
 * Replaces the string-templated `$runId-s$stageIndex-$stepIndex` hidden contract
 * in [com.pipeline.v2.application.PipelineRun] (F01 HIGH finding from M3-R3 debt-report).
 *
 * The format is designed to be lexicographically sortable by runId, stage, then step,
 * which enables efficient range queries on the journal.
 *
 * @param runId The pipeline run identifier.
 * @param stageIndex The 0-based stage index within the pipeline.
 * @param stepIndex The 0-based step index within the stage.
 */
data class OpId(
    val runId: String,
    val stageIndex: Int,
    val stepIndex: Int,
) {
    /**
     * Formats this OpId into the canonical string representation.
     * @return String in format "$runId-s$stageIndex-$stepIndex"
     */
    fun format(): String = "$runId-s$stageIndex-$stepIndex"

    companion object {
        private val PATTERN = Regex("^(.+)-s(\\d+)-(\\d+)$")

        /**
         * Parses a string representation into an [OpId].
         *
         * @param s The string to parse, expected in format "$runId-s$stageIndex-$stepIndex".
         * @return An [OpId] if parsing succeeds, or `null` if the format is invalid.
         */
        fun parse(s: String): OpId? {
            val match = PATTERN.matchEntire(s) ?: return null
            val (runId, sIdx, stepIdx) = match.destructured
            return OpId(runId, sIdx.toInt(), stepIdx.toInt())
        }
    }
}
