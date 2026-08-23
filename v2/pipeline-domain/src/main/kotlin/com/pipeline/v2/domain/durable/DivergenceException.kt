package com.pipeline.v2.domain.durable

/**
 * Thrown when a durable operation's current fingerprint does not match the journaled fingerprint.
 *
 * This is the fail-closed mechanism: if the input or step configuration has changed
 * since the last execution, the operation will NOT silently produce a different result.
 * Instead, this exception is thrown and the pipeline aborts.
 *
 * @param expected The fingerprint that was recorded in the journal.
 * @param actual   The fingerprint computed from the current input.
 * @param opId     The operation identifier.
 * @param runId    The run identifier.
 * @param stageIndex The stage index at which divergence was detected.
 *
 * @see <a href="design.md §E4-05">Design §E4-05</a>
 */
class DivergenceException(
    val expected: Fingerprint,
    val actual: Fingerprint,
    val opId: String,
    val runId: String,
    val stageIndex: Int,
) : RuntimeException(
    buildErrorMessage(expected, actual, opId, runId, stageIndex)
) {
    companion object {
        private fun buildErrorMessage(
            expected: Fingerprint,
            actual: Fingerprint,
            opId: String,
            runId: String,
            stageIndex: Int,
        ): String {
            return "Divergence on op=$opId run=$runId stage=$stageIndex: " +
                "expected=${expected.hex} actual=${actual.hex}"
        }
    }
}
