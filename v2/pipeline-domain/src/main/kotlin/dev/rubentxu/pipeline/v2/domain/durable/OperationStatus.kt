package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Closed state machine for the lifecycle of a durable operation.
 *
 * Valid transitions:
 * - PENDING → RUNNING
 * - RUNNING → SUCCEEDED | FAILED | ABORTED | DIVERGENT | LOST
 * - Any terminal state (SUCCEEDED, FAILED, ABORTED, DIVERGENT, LOST) is final.
 *
 * ## LOST State (ML-R1 / ADR-0046)
 *
 * LOST is a terminal state used when a durable process (e.g., `sh` step) was
 * executing but the worker process died mid-execution. Unlike SUCCEEDED/FAILED
 * which imply the process completed, LOST means we cannot determine the outcome:
 *
 * - The subprocess may still be running detached, or
 * - The subprocess may have crashed, or
 * - The result.txt file may not exist yet
 *
 * Per UAT-REC-002: fail-closed — LOST never implies success. Reconciliation
 * must not assume the step completed; a new attempt must be scheduled with
 * appropriate policy.
 *
 * @see <a href="design.md §E4-01">Design §E4-01</a>
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 */
enum class OperationStatus {
    /** Operation has been created but execution has not started. */
    PENDING,

    /** Operation is currently executing. */
    RUNNING,

    /** Operation completed successfully with a cached output. */
    SUCCEEDED,

    /** Operation executed but failed. */
    FAILED,

    /** Operation was deliberately aborted by policy. */
    ABORTED,

    /** Operation produced a result that diverged from the journaled fingerprint. */
    DIVERGENT,

    /**
     * Operation's worker/process was lost mid-execution.
     *
     * Used by durable shell steps (ML-R1) when:
     * - The JVM worker crashed during a `sh` step, OR
     * - The subprocess was detached (nohup) but result.txt was never written, AND
     * - Heartbeat detection determined the process is stale (> HEARTBEAT_CHECK_INTERVAL + HEARTBEAT_MINIMUM_DELTA)
     *
     * Fail-closed: LOST never implies success. The reconciler must apply policy
     * (typically: re-run with same fingerprint, never assume completion).
     */
    LOST;

    companion object {
        private val terminalStates = setOf(SUCCEEDED, FAILED, ABORTED, DIVERGENT, LOST)

        /**
         * Enforces the closed state machine transition rules.
         *
         * @param from The current state.
         * @param to   The target state.
         * @return [kotlin.Result.success] if the transition is valid,
         *         [kotlin.Result.failure] with [IllegalStateException] otherwise.
         */
        fun transition(from: OperationStatus, to: OperationStatus): kotlin.Result<Unit> {
            return when {
                from == to -> kotlin.Result.success(Unit)
                from in terminalStates -> kotlin.Result.failure(IllegalStateException("Cannot transition from terminal state $from"))
                from == PENDING && to == RUNNING -> kotlin.Result.success(Unit)
                from == RUNNING && to in terminalStates -> kotlin.Result.success(Unit)
                else -> kotlin.Result.failure(IllegalStateException("Invalid transition from $from to $to"))
            }
        }
    }
}
