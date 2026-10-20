package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilControlRowSnapshot
// WaitUntilControlState is in the same package — no import needed.

/**
 * WU-G5R.4 / WU-G5R.5: durable control journal for the waitUntil predicate polling loop.
 *
 * ## Single-writer law
 * The dispatch loop in [CanonicalDurableRunCoordinator] is the only caller of journal
 * operations. Child executors, step handlers, and event projectors MUST NOT mutate
 * the control file.
 *
 * ## Persist-before-effects contract
 * A journal operation writes its state to durable storage BEFORE returning. The caller
 * MUST launch any child effect only after the operation has returned without throwing.
 *
 * ## Scope
 * Created in WU-G5R.5. The interface is the wiring point introduced in WU-G5R.4.
 * The nullable default (`= null`) in the coordinator preserves all existing bare
 * constructions.
 */
interface WaitUntilControlJournal {

    /**
     * Persist a control row indicating that poll attempt [attempt] has started
     * with the given [fingerprint] and aggregate [status] (typically
     * [OperationStatus.RUNNING] or [OperationStatus.PENDING]).
     *
     * Idempotent for the same (controlOpId, attempt, fingerprint) triple.
     * A second call with a different fingerprint throws
     * [WaitUntilControlJournalDivergenceException].
     *
     * ## Persist-before-effects
     * MUST be called BEFORE launching any predicate body effect for this attempt.
     */
    fun beginAttempt(
        controlOpId: String,
        attempt: Int,
        currentBackoffMs: Long,
        fingerprint: Fingerprint,
        status: OperationStatus,
    )

    /**
     * Update the aggregate status of an existing attempt. Throws
     * [WaitUntilControlJournalDivergenceException] if the persisted fingerprint
     * differs from [fingerprint]. The control row is rewritten atomically.
     */
    fun updateStatus(
        controlOpId: String,
        attempt: Int,
        status: OperationStatus,
        fingerprint: Fingerprint,
    )

    /**
     * Read the durable state for one waitUntil logical invocation.
     *
     * Returns [WaitUntilControlState] carrying the persisted control rows.
     */
    fun readState(
        controlOpId: String,
        initialRecurrencePeriodMs: Long,
        maxBackoffMs: Long,
        currentFingerprint: Fingerprint,
    ): WaitUntilControlState

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Thrown when a write would produce a control row whose fingerprint conflicts
 * with the persisted one. Fail-closed on contract divergence.
 */
class WaitUntilControlJournalDivergenceException(message: String) : IllegalStateException(message)
