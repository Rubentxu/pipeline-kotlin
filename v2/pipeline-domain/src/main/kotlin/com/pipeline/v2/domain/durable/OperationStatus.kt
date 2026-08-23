package com.pipeline.v2.domain.durable

/**
 * Closed state machine for the lifecycle of a durable operation.
 *
 * Valid transitions:
 * - PENDING → RUNNING
 * - RUNNING → SUCCEEDED | FAILED | ABORTED | DIVERGENT
 * - Any terminal state (SUCCEEDED, FAILED, ABORTED, DIVERGENT) is final.
 *
 * @see <a href="design.md §E4-01">Design §E4-01</a>
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
    DIVERGENT;

    companion object {
        private val terminalStates = setOf(SUCCEEDED, FAILED, ABORTED, DIVERGENT)

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
