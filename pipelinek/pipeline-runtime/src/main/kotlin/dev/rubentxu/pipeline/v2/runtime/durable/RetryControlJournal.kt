package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus

/**
 * Durable control journal for the retry aggregate.
 *
 * ## WHY A PORT AND NOT THE CLASS THAT WRITES FILES
 *
 * The retry aggregate's authority is the LOOP: reconcile, decide, invoke,
 * fold, persist. What it persists is durable state, and durable state may
 * live in more than one place. The engine does not care which: it reads the
 * state and writes the transitions, and nothing else.
 *
 * Naming the file-based class in the engine's constructor would have made the
 * storage part of the authority, and the authority part of the storage. A row
 * about the loop would then only ever be exercisable against a directory,
 * and the half of the behaviour that has nothing to do with directories — the
 * loop that decides whether an effect happens again — would have no
 * instrument of its own.
 *
 * ## Single-writer law
 *
 * The retry aggregate loop is the only caller of journal operations. Child
 * executors, step handlers and event projectors MUST NOT mutate the control
 * file.
 *
 * ## Persist-before-effects contract
 *
 * A journal operation writes its state BEFORE returning. The caller MUST
 * launch the body of an attempt only after [beginAttempt] has returned
 * without throwing: an attempt that ran without a durable row would leave
 * behind an effect nobody can reconcile.
 */
interface RetryControlJournal {

    /**
     * Persist a control row saying that attempt [attempt] has started with
     * the given [fingerprint] and aggregate [status].
     *
     * Idempotent for the same (controlOpId, attempt, fingerprint) triple. A
     * second call with a different fingerprint is a contract divergence and
     * throws [RetryControlJournalDivergenceException].
     */
    fun beginAttempt(
        controlOpId: String,
        attempt: Int,
        fingerprint: Fingerprint,
        status: OperationStatus,
    )

    /**
     * Update the aggregate status of an existing attempt.
     *
     * Throws [RetryControlJournalDivergenceException] if the persisted
     * fingerprint differs from [fingerprint].
     */
    fun updateStatus(
        controlOpId: String,
        attempt: Int,
        status: OperationStatus,
        fingerprint: Fingerprint,
    )

    /**
     * Read the durable state for one retry logical invocation.
     *
     * The returned [RetryControlState] carries the persisted control rows,
     * the child rows grouped by attempt, and the child rows that carry no
     * control row of their own.
     */
    fun readState(
        controlOpId: String,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        parentBodyPath: List<BlockSegment>,
        maxAttempts: Int,
        currentFingerprint: Fingerprint,
    ): RetryControlState
}
