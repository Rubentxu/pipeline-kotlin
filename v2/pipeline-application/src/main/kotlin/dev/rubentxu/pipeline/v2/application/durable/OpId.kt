package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment

/**
 * Typed operation identifier. Format: "$runId-s$stageIndex-$stepIndex[-b$branchIndex][-bp{N}-{seg}...]".
 *
 * Replaces the string-templated `$runId-s$stageIndex-$stepIndex` hidden contract
 * in [dev.rubentxu.pipeline.v2.application.PipelineRun] (F01 HIGH finding from M3-R3 debt-report).
 *
 * The format is designed to be lexicographically sortable by runId, stage, then step,
 * which enables efficient range queries on the journal.
 *
 * When branchIndex is non-null, the format extends to: "$runId-s$stageIndex-$stepIndex-b$branchIndex"
 *
 * When bodyPath is non-empty, the format extends to: "$runId-s$stageIndex-$stepIndex[-b$branchIndex]-bp{N}-{idx}:{pluginId}..."
 *
 * @param runId The pipeline run identifier.
 * @param stageIndex The 0-based stage index within the pipeline.
 * @param stepIndex The 0-based step index within the stage.
 * @param branchIndex The optional 0-based branch index for parallel frame execution.
 * @param bodyPath The optional list of block segments for body dispatch (length-prefix per ADR-0066 §1).
 */
data class OpId(
    val runId: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val branchIndex: Int? = null,
    val bodyPath: List<BlockSegment> = emptyList(),
) {
    /**
     * Formats this OpId into the canonical string representation with length-prefix bodyPath.
     * @return String in format "$runId-s$stageIndex-$stepIndex[-b$branchIndex][-bp{N}-{seg}...]"
     */
    fun format(): String = buildString {
        append(runId); append("-s"); append(stageIndex); append('-'); append(stepIndex)
        if (branchIndex != null) { append("-b"); append(branchIndex) }
        if (bodyPath.isNotEmpty()) {
            append("-bp"); append(bodyPath.size)
            bodyPath.forEach { seg -> append('-'); append(seg.encoded) }
        }
    }

    /**
     * Returns the string representation (same as format).
     */
    override fun toString(): String = format()

    /**
     * Legacy format that is byte-identical to pre-EM-4 format for empty bodyPath.
     * Use this for linear-path operations to preserve existing journal keys.
     */
    fun legacyFormat(): String =
        if (branchIndex != null) "$runId-s$stageIndex-$stepIndex-b$branchIndex"
        else "$runId-s$stageIndex-$stepIndex"

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
         * Pattern for OpId with optional branch and bodyPath:
         * "runId-s{stageIndex}-{stepIndex}[-b{branchIndex}][-bp{N}-{seg0}-{seg1}-...]"
         * Segments are encoded as "{index}:{pluginStepId}".
         */
        private val BODY_PATTERN = Regex("""^(.+)-s(\d+)-(\d+)(-b(\d+))?(-bp(\d+)(.+))?$""")

        /**
         * Parses a string representation into an [OpId].
         *
         * @param s The string to parse, expected in format "$runId-s$stageIndex-$stepIndex[-b$branchIndex][-bp{N}-...]".
         * @return An [OpId] if parsing succeeds, or `null` if the format is invalid.
         */
        fun parse(s: String): OpId? {
            // First try body-aware pattern (most general)
            val bodyMatch = BODY_PATTERN.matchEntire(s) ?: return null
            val (runId, sIdx, stepIdx, _, branchIdx, _, bodyCountStr, bodySegs) = bodyMatch.destructured

            val bodyPath = if (bodySegs.isNotEmpty() && bodyCountStr.isNotEmpty()) {
                val count = bodyCountStr.toIntOrNull() ?: 0
                // Split by hyphen, but each segment is "index:pluginId" (contains colon but not hyphen)
                // The segments were joined with hyphens, so we split and filter
                val allSegments = bodySegs.split("-").filter { it.isNotBlank() }
                allSegments.take(count).mapNotNull { seg ->
                    val parts = seg.split(":")
                    if (parts.size == 2) {
                        val index = parts[0].toIntOrNull()
                        val pluginId = parts[1]
                        if (index != null && pluginId.isNotBlank()) {
                            BlockSegment(index, dev.rubentxu.pipeline.v2.domain.PluginStepId(pluginId))
                        } else null
                    } else null
                }
            } else emptyList()

            return OpId(
                runId,
                sIdx.toInt(),
                stepIdx.toInt(),
                branchIdx.takeIf { it.isNotEmpty() }?.toInt(),
                bodyPath
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
