package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Closed state machine for the lifecycle of a durable operation.
 *
 * Valid transitions:
 * - PENDING → RUNNING
 * - RUNNING → SUCCEEDED | FAILED | UNSTABLE | ABORTED | DIVERGENT | LOST | FAILED_TIMEOUT
 * - Any terminal state (SUCCEEDED, FAILED, UNSTABLE, ABORTED, DIVERGENT, LOST, FAILED_TIMEOUT) is final.
 *
 * A transition to the state the operation is already in is accepted for every state, terminal or
 * not: re-asserting a status is idempotent, not a move. Finality means "cannot reach a *different*
 * state", which is what `OperationStatusTest` pins.
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
 * ## FAILED_TIMEOUT State (ML-R2 / ADR-0047)
 *
 * FAILED_TIMEOUT is a terminal state used when a durable shell step's watchdog
 * timer fires and kills the process tree via SIGKILL. Unlike FAILED (script
 * ran to completion with non-zero exit), FAILED_TIMEOUT means the script was
 * killed mid-execution by the timeout deadline.
 *
 * The distinction from LOST is important: LOST means worker crash (unknown outcome),
 * FAILED_TIMEOUT means deadline enforcement (known killed, unknown if it would have succeeded).
 *
 * @see <a href="design.md §E4-01">Design §E4-01</a>
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 * @see <a href="ADR-0047">ADR-0047 — FAILED_TIMEOUT Terminal State</a>
 */
enum class OperationStatus {
    /** Operation has been created but execution has not started. */
    PENDING,

    /** Operation is currently executing. */
    RUNNING,

    /** Operation completed successfully with a cached output. */
    SUCCEEDED,

    /** Operation executed but failed with a non-zero exit code. */
    FAILED,

    /**
     * Operation ran to completion and declared itself UNSTABLE.
     *
     * This is NOT a failure and the distinction is the whole point of the case. Jenkins — the
     * baseline in `AGENTS.md` STEP SEMANTICS — has `unstable` as a build result of its own, distinct
     * from `failure`, and that is what `warnError` and `unstable` exist to produce: the work ran,
     * and something in it deserves attention without aborting the run.
     *
     * The case was absent here, and the two semantic layers above the durable one already had it:
     * `StepOutcome.Unstable` and `BranchTerminal.Unstable` are both their own cases. So the durable
     * layer was the only one still collapsing the fact, and it was collapsing it three different
     * ways for the same condition — `FAILED` through `StepOutcome`, `ABORTED` through
     * `BranchTerminal`, and a persisted `FAILED` through the retry engine. A child row and the
     * aggregate row of the same branch disagreed about what had happened, and neither matched the
     * run-level terminal the CLI already reported as `unstable`.
     *
     * It is terminal (see [isTerminal]) and it is NOT a poll failure (see [isPollFailure]): the
     * operation finished, so it never advances an iteration and never re-runs. Collapsing it into
     * `FAILED` on the way in would have turned a declared-unstable run into a declared-failed one,
     * which is a behaviour change disguised as a representation change.
     */
    UNSTABLE,

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
    LOST,

    /**
     * Operation was killed by deadline timeout (SIGKILL via watchdog).
     *
     * Used by durable shell steps (ML-R2) when:
     * - The step had a timeout deadline set (step.timeoutMillis or stage.options.timeout)
     * - The watchdog timer fired before result.txt was written
     * - The process tree was killed via `setsid pgid kill -9`
     * - The timeout.flag file was written BEFORE the kill (per TMO-S-005)
     *
     * FAILED_TIMEOUT is terminal: the step will not be re-executed on resume.
     * The fingerprint is preserved (same script, same attempt) but the terminal
     * status prevents re-execution per TMO-S-007 / DSE-S-025.
     *
     * Distinct from LOST: LOST = unknown outcome (worker crash);
     * FAILED_TIMEOUT = known killed by deadline (distinguishable via timeout.flag).
     */
    FAILED_TIMEOUT;

    /**
     * Returns true if this status is terminal (final).
     * Terminal states cannot transition to any other state.
     */
    val isTerminal: Boolean
        get() = this in terminalStates

    /**
     * Returns true if this status indicates an operation that ran to completion
     * (possibly with a failure) and should advance the iteration count.
     *
     * Does NOT return true for:
     * - PENDING (not yet started)
     * - RUNNING (in flight)
     * - SUCCEEDED (predicate satisfied)
     * - UNSTABLE (ran to completion and declared itself unstable — finished, not a failed poll)
     *
     * Returns true for:
     * - FAILED (body script failed)
     * - FAILED_TIMEOUT (deadline exceeded — ran but was killed)
     * - ABORTED (explicit cancellation)
     * - DIVERGENT (fingerprint mismatch)
     * - LOST (worker crashed mid-flight)
     *
     * Used by both [RetryReconciler] and [WaitUntilReconciler] to detect that an
     * attempt completed and a subsequent attempt is needed.
     */
    val isPollFailure: Boolean
        get() = this == FAILED || this == FAILED_TIMEOUT || this == ABORTED || this == DIVERGENT || this == LOST

    companion object {
        private val terminalStates = setOf(SUCCEEDED, FAILED, UNSTABLE, ABORTED, DIVERGENT, LOST, FAILED_TIMEOUT)

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
