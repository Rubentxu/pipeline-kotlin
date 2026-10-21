package dev.rubentxu.pipeline.v2.runtime.control

/**
 * The identity of a run's cancellation receipt.
 *
 * ## What this is
 * The receipt is the journal row that says "this run was cancelled, and here
 * is why". Its id is RESERVED by the product: it is not one of the pipeline's
 * operation ids (`{runId}-s{stage}-{step}`), so it can never collide with a
 * step, and a reader that looks for operations by shape does not mistake it
 * for one.
 *
 * ## Why the vocabulary lives here and not inside the adapter
 * Because there are two readers of that id and they must not drift apart: the
 * cancel WRITES the receipt, and the recover READS it to know that a run was
 * cancelled — a run whose work was interrupted on purpose is not a run to
 * re-attach, and the recover says so instead of inviting a resume that could
 * repeat an effect the interrupted step may already have had.
 *
 * One producer and one consumer of the shape, one place that names it.
 */
object CancelReceipt {

    /**
     * The operation id of a run's cancellation receipt.
     *
     * @param runId the run the receipt belongs to.
     * @return the reserved id of that run's receipt.
     */
    fun opIdOf(runId: String): String = "cancel-$runId"

    /**
     * Whether this journal row is a run's cancellation receipt.
     *
     * @param opId the row's operation id.
     * @param runId the run the receipt would belong to.
     * @return true when the row IS that run's receipt.
     */
    fun isReceiptOf(opId: String, runId: String): Boolean = opId == opIdOf(runId)
}
