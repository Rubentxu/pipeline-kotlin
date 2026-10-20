package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.runtime.recover.RuntimeRecoverDecision.OperationOutcome

/**
 * The vocabulary the recover adapter uses to write a run's outcome into the journal.
 *
 * ## Why this is not inside the adapter
 *
 * These four mappings are the only place where "what the run did" becomes "what the journal
 * will say". They were private methods of `RuntimeRecoverPortStoreAdapter`, and a private
 * method inside a class that composes a journal, a cursor, a lease and an output store can
 * only be reached by running all of them: the adapter's `recover()` takes five collaborators
 * and touches the filesystem. So the mapping that decides what a recovered run looks like in
 * the journal had no observer at all.
 *
 * The mappings are pure, so they were lifted out. `recover()` still composes the same
 * authorities in the same order; what changed is that the words it writes are now a thing
 * that can be asked a question.
 *
 * ## The rules, and the one that carries the weight
 *
 * - [operationOutcomeOf] says what a journalled operation MEANS to the recover decider.
 *   `FAILED`, `FAILED_TIMEOUT` and `DIVERGENT` all mean `Failed`: a step that was killed by
 *   its deadline did not produce a result, and a step that diverged did not produce the
 *   result it promised. Neither is a success, and neither is `Unstable` — which means the
 *   work RAN and deserves attention, a different fact.
 * - [recoveredStatusOf] says what a recovered TERMINAL is journalled as. Here the rule that
 *   carries the weight is that **nothing unknown is journalled as a success**: a terminal the
 *   runtime could not classify (`Other`) becomes `FAILED`, and a `Cancelled` terminal becomes
 *   `ABORTED` — the durable vocabulary has no `CANCELLED`, and `ABORTED` is the state that
 *   says "this run did not finish", which is what cancellation means.
 * - [recoveredOutcomeNameOf] is the word written next to the status, so an operator reading
 *   the journal sees `cancelled` and not `failed`.
 * - [terminalAtMsOf] is the time the terminal carries. It has one answer per case, and it is
 *   here so that "when did it end" has one reader too.
 */
internal fun operationOutcomeOf(status: OperationStatus): OperationOutcome = when (status) {
    OperationStatus.SUCCEEDED -> OperationOutcome.Succeeded
    OperationStatus.FAILED,
    OperationStatus.FAILED_TIMEOUT,
    OperationStatus.DIVERGENT -> OperationOutcome.Failed
    OperationStatus.UNSTABLE -> OperationOutcome.Unstable
    OperationStatus.ABORTED -> OperationOutcome.Aborted
    OperationStatus.LOST -> OperationOutcome.Other("lost")
    OperationStatus.RUNNING -> OperationOutcome.Running
    OperationStatus.PENDING -> OperationOutcome.Other("pending")
}

/** The word written beside the journalled status of a recovered terminal. */
internal fun recoveredOutcomeNameOf(terminal: TerminalObservation): String = when (terminal) {
    is TerminalObservation.Succeeded -> "succeeded"
    is TerminalObservation.Failed -> terminal.failureKind
    is TerminalObservation.Unstable -> "unstable"
    is TerminalObservation.Aborted -> "aborted"
    is TerminalObservation.Cancelled -> "cancelled"
    is TerminalObservation.Other -> terminal.rawOutcome
}

/** The status a recovered terminal is journalled as. */
internal fun recoveredStatusOf(terminal: TerminalObservation): OperationStatus = when (terminal) {
    is TerminalObservation.Succeeded -> OperationStatus.SUCCEEDED
    is TerminalObservation.Failed -> OperationStatus.FAILED
    is TerminalObservation.Unstable -> OperationStatus.UNSTABLE
    is TerminalObservation.Aborted -> OperationStatus.ABORTED
    is TerminalObservation.Cancelled -> OperationStatus.ABORTED
    is TerminalObservation.Other -> OperationStatus.FAILED
}

/** When the terminal ended, in epoch millis. */
internal fun terminalAtMsOf(terminal: TerminalObservation): Long = when (terminal) {
    is TerminalObservation.Succeeded -> terminal.terminalAtMs
    is TerminalObservation.Failed -> terminal.terminalAtMs
    is TerminalObservation.Unstable -> terminal.terminalAtMs
    is TerminalObservation.Aborted -> terminal.terminalAtMs
    is TerminalObservation.Cancelled -> terminal.terminalAtMs
    is TerminalObservation.Other -> terminal.terminalAtMs
}
