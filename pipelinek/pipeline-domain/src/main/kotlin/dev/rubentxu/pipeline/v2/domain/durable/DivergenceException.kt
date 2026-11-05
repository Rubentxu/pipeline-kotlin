package dev.rubentxu.pipeline.v2.domain.durable

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
    /**
     * Holds the wording of the divergence message.
     *
     * The message names the operation, the run and the stage where the
     * divergence was found, and prints both fingerprints — the one the journal
     * holds and the one the current input computes — so the reader can see
     * which side moved. It is built before the exception exists, because the
     * message IS the exception.
     */
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
