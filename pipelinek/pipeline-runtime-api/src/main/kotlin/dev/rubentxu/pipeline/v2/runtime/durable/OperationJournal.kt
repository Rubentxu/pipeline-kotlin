package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput

/**
 * The record of what a run executed.
 *
 * ## What this port is
 *
 * A durable operation is one whose effect cannot be repeated safely, so it has to be
 * written down BEFORE it is believed. This port is that write: it records an operation
 * as RUNNING before it runs and as a terminal outcome once it is over, and it answers
 * what was journaled.
 *
 * The store behind the port decides nothing about the operation. It writes what it is
 * given and hands it back; whether an outcome is acceptable, whether it may be retried
 * and what it means for the run are the domain's calls, made over the values this port
 * returns.
 *
 * ## The order a consumer owes this port
 *
 * [beginOperation] writes the row that says the operation is in flight, and it is what
 * makes a run that died mid-operation visible as such: a RUNNING row with no terminal
 * status is a fact about a process that was killed, and it is not the same fact as a
 * row that never started.
 *
 * ## The one rule that is not the consumer's to break
 *
 * The replay cursor may only be advanced AFTER an append has returned. A cursor that
 * points at an operation which was never persisted resumes a run at a place the run
 * never reached, and the operation it skipped is an effect the run believes it already
 * had.
 */
interface OperationJournal {
    /**
     * Appends a durable operation to the journal.
     *
     * @param op The durable operation to journal.
     * @param deadlineMs The deadline timestamp in milliseconds (epoch),
     *                   or null if no timeout is set.
     */
    fun append(op: DurableOperation, deadlineMs: Long? = null)

    /**
     * Retrieves the latest journaled operation by its [opId].
     * Returns the entry with the highest attempt number.
     *
     * @param opId The operation identifier.
     * @return The [DurableOperation] if found, or `null` if no entry exists.
     */
    fun get(opId: String): DurableOperation?

    /**
     * Retrieves a journaled operation by its [opId] and [attempt].
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The [DurableOperation] if found, or `null` if no entry exists.
     */
    fun get(opId: String, attempt: Int): DurableOperation?

    /**
     * Lists all journaled operations for a given [runId], ordered by [created_at] ascending.
     *
     * @param runId The run identifier.
     * @return A [List] of [DurableOperation] in execution order.
     */
    fun listForRun(runId: String): List<DurableOperation>

    /**
     * Retrieves the journaled deadline timestamp (epoch ms) for a given operation attempt.
     * Returns null if no deadline was set (timeout = null), or if no journal entry exists.
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The deadline timestamp in milliseconds, or `null` if not set or not found.
     */
    fun getDeadlineMs(opId: String, attempt: Int): Long?

    /**
     * Retrieves the ended_at timestamp (epoch ms) for a given operation attempt.
     * Returns null if the row does not exist or ended_at is NULL.
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The ended_at timestamp in milliseconds, or `null` if not set or not found.
     */
    fun getEndedAt(opId: String, attempt: Int): Long?

    /**
     * Retrieves the started_at timestamp (epoch ms) for a given operation attempt.
     * Returns null if the row does not exist or started_at is NULL.
     *
     * @param opId The operation identifier.
     * @param attempt The 1-based attempt number.
     * @return The started_at timestamp in milliseconds, or `null` if not set or not found.
     */
    fun getStartedAt(opId: String, attempt: Int): Long?

    /**
     * Begins a durable operation by writing a RUNNING row to the journal.
     *
     * This is the first half of the two-phase journal pattern (beginOperation + append).
     * Writing RUNNING before executing the step enables fail-closed reconciliation
     * on restart: if a RUNNING row exists with no terminal status, the subprocess
     * was killed mid-execution and must not be silently trusted.
     *
     * ## The row is attributed to its run AT BIRTH
     *
     * The run id is read from [OperationInput.runId] and written with the row,
     * because the RUNNING row is exactly the row a reader needs in order to
     * know that a run is still in flight. An unattributed RUNNING row is
     * invisible to [listForRun] until the operation terminates, which made a
     * run that died mid-operation look FINISHED to every reader that could
     * not repair the column -- and made the answer depend on which store lane
     * was asking.
     *
     * ## Caller MUST pre-format opId
     *
     * The caller is responsible for formatting the opId with any branch index using
     * [OpId.format()]. The journal MUST NOT derive or append a branch suffix.
     * This ensures the journal is never responsible for formatting operation IDs.
     *
     * @param opId The operation identifier. MUST be pre-formatted by the caller
     *              using [OpId.format()] if it includes a branch index.
     * @param attempt The 1-based attempt number.
     * @param fingerprint The SHA-256 fingerprint of the operation input.
     * @param inputJson JSON-serialized [OperationInput].
     * @param deadlineMs The deadline timestamp in milliseconds, or null if no timeout.
     * @throws IllegalStateException if a row already exists for (opId, attempt).
     */
    fun beginOperation(
        opId: String,
        attempt: Int,
        fingerprint: String,
        inputJson: String,
        deadlineMs: Long? = null,
    )
}
