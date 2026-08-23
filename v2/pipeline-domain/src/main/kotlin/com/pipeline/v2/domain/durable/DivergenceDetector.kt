package com.pipeline.v2.domain.durable

/**
 * Fail-closed divergence detector for durable operations.
 *
 * Compares the [fingerprint] of the [current] operation against the [journaled]
 * operation (if any). If fingerprints differ, throws [DivergenceException].
 *
 * ## Fail-closed semantics
 *
 * - **No journal entry**: Returns [Result.success] — first attempt, no divergence possible.
 * - **Fingerprints match**: Returns [Result.success] — operation is reproducible.
 * - **Fingerprints differ**: Returns [Result.failure] with [DivergenceException] —
 *   the operation has diverged and the pipeline must abort.
 *
 * @see <a href="design.md §E4-05">Design §E4-05</a>
 */
class DivergenceDetector {
    /**
     * Checks whether the current operation has diverged from the journaled state.
     *
     * @param current   The operation as currently configured.
     * @param journaled The operation as recorded in the journal, or `null` if no entry exists.
     * @return [Result.success] if no divergence, [Result.failure] with [DivergenceException]
     *         if fingerprints differ.
     */
    fun check(current: DurableOperation, journaled: DurableOperation?): kotlin.Result<Unit> {
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
