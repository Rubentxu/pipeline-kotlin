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
 * ## The exception every implementation may make, and no other
 *
 * A digest that is already journalized is read with the spelling it was written with, so an
 * implementation MAY recognise an older spelling of the SAME operation and answer
 * [Result.success]. What it may NOT do is answer [Result.success] for a different program: the
 * fail-closed rule is about the PROGRAM, and only the program. The implementation shipped here
 * recognises exactly one older spelling, under three conditions — see
 * [StrictFingerprintDivergenceDetector].
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
 * Compares the [DurableOperation.fingerprint] of the `current` operation against the `journaled`
 * operation (if any). If fingerprints differ, throws [DivergenceException].
 *
 * ## The one spelling this gate still accepts
 *
 * A journaled digest is READ with the spelling it was written with; a digest that is already
 * durable is never recalculated as if it were new. One older spelling is recognised — the one a
 * refusal at the typed admission used to write, which carried the `RERUN` policy instead of the
 * declared one — and a row that carries it, for the SAME program, is the same operation rather
 * than a divergence. See [isOlderSpelling] for the two conditions that keep the window narrow.
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

        return if (current.fingerprint == journaled.fingerprint || isOlderSpelling(current, journaled)) {
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

    /**
     * Whether the journaled row is THIS operation under an OLDER spelling of its identity.
     *
     * ## The spelling that changed
     *
     * A refusal at the typed admission used to write the row with the `RERUN` policy, whatever
     * the step had declared. The policy is part of the identity (it is hashed into it), so a
     * step that declared `MEMOIZED` was journaled under a digest that no admission of that step
     * would ever produce. The next attempt of the same run computed the declared policy's
     * digest, compared the two, and declared a DIVERGENCE — for a program that had not changed
     * by a single byte, and for an operation that had never run.
     *
     * ## How the row is READ
     *
     * A digest that is already journalized is read with the spelling it was written with; it is
     * not recalculated as if it were new. The row carries its own input, so the older spelling
     * can be recomputed FROM THE ROW and compared with the digest the row carries. When they
     * agree, the row is the same operation under the older spelling, and the operation has not
     * diverged.
     *
     * ## How narrow the window is
     *
     * Three conditions, and all are needed:
     *
     *  - the row's input is the current operation's input — the PROGRAM is the same. A different
     *    program is a divergence, and it stays one;
     *  - the row's attempt is at least one — the older spelling is recomputed under that attempt,
     *    and an attempt below one is not an attempt this repository writes. The row is refused
     *    rather than recomputed under an attempt whose identity cannot exist: a fail-closed gate
     *    that threw while reading a row would answer the run with a crash where it owes it a
     *    divergence;
     *  - the digest the row carries is exactly the `RERUN` spelling of that input — the row was
     *    written by the build that spelled it that way. A row whose digest is the declared
     *    policy's spelling is not in the window, so a step whose declared policy CHANGED is
     *    still a divergence.
     *
     * The policy itself remains the replay layer's business: this gate decides whether the
     * operation is the same one, and a policy that forbids re-execution is enforced where the
     * policy is read.
     */
    private fun isOlderSpelling(current: DurableOperation, journaled: DurableOperation): Boolean {
        if (journaled.input != current.input) {
            return false
        }
        if (journaled.attempt < 1) {
            return false
        }
        val older = Fingerprint.compute(
            journaled.input,
            journaled.input.stepId,
            ReplayPolicy.RERUN,
            journaled.attempt,
        )
        return journaled.fingerprint == older
    }
}
