package dev.rubentxu.pipeline.v2.runtime.recover

/**
 * Bounded audit of what recovery did.
 *
 * The recover port reports what it observed and what it wrote so a caller can
 * verify the "no-rerun" invariant on its own.
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §5.3.
 */
data class RecoverReport(
    /** Number of journal rows reconciled (terminal rows committed). */
    val journalRowsCommitted: Int,
    /** Number of frames appended by `OutputFrameIndex.recoverUnframedBytes`. */
    val framesAppended: Int,
    /** Number of streams reconciled by `OutputRecoveryPort.recover`. */
    val streamsReconciled: Int,
    /** Whether the cursor advanced. */
    val cursorAdvanced: Boolean,
    /** The replay decisions observed per operation, in order. */
    val replayDecisions: List<String>,
) {
    companion object {
        /** A zero-valued report for the `dryRun` path. */
        val Empty: RecoverReport = RecoverReport(
            journalRowsCommitted = 0,
            framesAppended = 0,
            streamsReconciled = 0,
            cursorAdvanced = false,
            replayDecisions = emptyList(),
        )
    }
}
