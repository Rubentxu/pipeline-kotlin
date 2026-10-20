package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Interface for fail-closed divergence detection.
 *
 * ## Contract
 *
 * This interface is stable for consumption.
 *
 * ## Fail-closed semantics
 *
 * - **No journal entry**: Returns [Result.success] — first attempt, no divergence possible.
 * - **Fingerprints match**: Returns [Result.success] — operation is reproducible.
 * - **Fingerprints differ**: Returns [Result.failure] with [DivergenceException] —
 *   the operation has diverged and the pipeline must abort.
 *
 */
interface DivergenceDetector {
    /**
     * Checks whether the current operation has diverged from the journaled state.
     *
     * @param current   The operation as currently configured.
     * @param journaled The operation as recorded in the journal, or `null` if no entry exists.
     * @return [Result.success] if no divergence, [Result.failure] with [DivergenceException]
     *         if fingerprints differ.
     */
    fun check(current: DurableOperation, journaled: DurableOperation?): kotlin.Result<Unit>
}

/**
 * Strict fingerprint-based divergence detector.
 *
 * Compares the [fingerprint] of the [current] operation against the [journaled]
 * operation (if any). If fingerprints differ, throws [DivergenceException].
 *
 */
class StrictFingerprintDivergenceDetector : DivergenceDetector {
    /**
     * Checks whether the current operation has diverged from the journaled state.
     *
     * @param current   The operation as currently configured.
     * @param journaled The operation as recorded in the journal, or `null` if no entry exists.
     * @return [Result.success] if no divergence, [Result.failure] with [DivergenceException]
     *         if fingerprints differ.
     */
    override fun check(current: DurableOperation, journaled: DurableOperation?): kotlin.Result<Unit> {
        // First attempt — no divergence possible.
        if (journaled == null) {
            return kotlin.Result.success(Unit)
        }

        return if (current.fingerprint == journaled.fingerprint) {
            kotlin.Result.success(Unit)
        } else {
            kotlin.Result.failure(
                DivergenceException(
                    expected = journaled.fingerprint,
                    actual = current.fingerprint,
                    opId = current.id,
                    runId = current.input.runId,
                    stageIndex = 0, // stage index is tracked by the caller via cursor
                )
            )
        }
    }
}
