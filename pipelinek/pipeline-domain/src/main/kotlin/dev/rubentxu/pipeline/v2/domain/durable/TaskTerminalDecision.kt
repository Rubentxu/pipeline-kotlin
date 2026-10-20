package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Pure, typed projection of a [TaskExecutionResult] into the smallest set of
 * shapes that distinguishes the three legitimate ways a single invocation
 * can end.
 * Why an ADT instead of the runtime's boolean pair:
 *  - `TaskExecutionResult` carries `(exitCode, timedOut, cancelled)`. The
 *    runtime's `init` already forbids `(timedOut && cancelled)`, but the
 *    pair still admits a fourth state in the *consumer*'s eyes:
 *    `(exitCode != 0 && timedOut)` ("exited with non-zero because the
 *    watchdog killed the process"). The legacy `when { result.timedOut ->
 *    Cancelled; result.cancelled -> Cancelled; else -> Exited }` collapses
 *    that fourth state into `Exited(exitCode = 137)`, losing the *cause*
 *    and forcing the consumer to re-derive it from the exit code.
 *  - This ADT makes that fourth state representable as `TimedOut` (the
 *    watchdog is the cause) and the three legitimate ones as `Exited`,
 *    `TimedOut`, `Cancelled`. `isSuccessFromDecision` is then a one-line
 *    fold rather than a string-or-int comparison the call sites are
 *    tempted to write.
 * **Purity:** no I/O, no clock, no filesystem, no randomness. The decider
 * is: the inputs are its only dependency. Tests live in
 * `pipeline-domain` and do not touch the process substrate.
 * **Exhaustiveness:** the three constructors are the only ways a single
 * invocation ends; adding a fourth is a deliberate API change and must
 * update both the decider and every consumer.
*/
sealed interface TaskTerminalDecision {
    /** The process exited on its own; `exitCode` is whatever the OS recorded. */
    data class Exited(val exitCode: Int, val elapsedMs: Long) : TaskTerminalDecision

    /** The watchdog killed the process tree because the timeout budget elapsed. */
    data class TimedOut(val elapsedMs: Long) : TaskTerminalDecision

    /** The run was cancelled (parent or operator) while the process was still alive. */
    data class Cancelled(val elapsedMs: Long) : TaskTerminalDecision
}

/**
 * Project a [TaskExecutionResult] into a [TaskTerminalDecision].
 * Precedence: `timedOut` > `cancelled` > `exited`. The runtime's `init` block
 * already refuses `timedOut && cancelled`, so the first two branches are
 * mutually exclusive in any well-formed input.
*/
fun decideTaskTerminal(result: TaskExecutionResult): TaskTerminalDecision {
    val elapsed = result.endedAtEpochMs - result.startedAtEpochMs
    return when {
        result.timedOut -> TaskTerminalDecision.TimedOut(elapsed)
        result.cancelled -> TaskTerminalDecision.Cancelled(elapsed)
        else -> TaskTerminalDecision.Exited(result.exitCode, elapsed)
    }
}

/**
 * `true` only when the process exited on its own with code zero.
 * Use this at the call site instead of `result.exitCode == 0` so a future
 * fourth decision shape (e.g. `Refused`) can be added without a silent
 * green. A change to this function is the single place a consumer's notion
 * of "success" lives.
*/
fun isSuccessFromDecision(decision: TaskTerminalDecision): Boolean =
    decision is TaskTerminalDecision.Exited && decision.exitCode == 0