package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished

/**
 * The exit decision of a run, and the report that goes with it.
 *
 * ## ONE owner, because two paths used to disagree
 *
 * A run reaches its outcome through one of two execution paths — with a durable journal, or
 * with volatile stores — and the CLI answers the same question in both. They used to answer it
 * DIFFERENTLY: the durable path printed the failure and then its cause, while the volatile path
 * printed the cause in the same line and never named the failure's kind. The same failing
 * pipeline therefore produced two different reports depending on whether the operator had a
 * database, and a suite that ran only one of them could not see that there was a choice to make.
 *
 * [runExit] is that single owner. Both worlds call it, so the report is decided once and cannot
 * drift, and the DECISION it returns is a case of the [CliExit] taxonomy rather than a boolean:
 * the case says what the exit MEANS, and the number the process ends with is read from it.
 *
 * ## Why the legacy branch is still here
 *
 * A run that produced no typed outcome — a compile-failure path, where no definition was ever
 * built — has only the events to answer with, and the last one of them says whether the run
 * finished. That is the branch [runExit] takes when there is no typed outcome, and it is the
 * SAME branch in both worlds: the two used to carry a copy each, and a copy is free to disagree
 * with the other one.
 *
 * @return the case that owns this run's exit.
 */
internal fun runExit(runOutcome: RunOutcome?, lastEvent: DomainEvent?): CliExit =
    if (runOutcome != null) {
        reportRunOutcome(runOutcome)
    } else {
        // The LEGACY path: no typed outcome was produced, so there are only two stories —
        // "it finished successfully" and "it did not". The "unstable" arm that used to live
        // here was UNREACHABLE: the word it compared against could only be those two, so the
        // arm could never run and only misled whoever read it.
        val succeeded = lastEvent is RunFinished && lastEvent.outcome == RunOutcome.SUCCESS_TOKEN
        if (succeeded) {
            System.err.println("Pipeline finished with SUCCESS")
            CliExit.SUCCESS
        } else {
            System.err.println("Pipeline finished with FAILURE")
            CliExit.PipelineFailed(message = "the run produced no typed outcome and did not finish successfully")
        }
    }

/**
 * Turns a run outcome into what the operator reads, and into the case that owns its exit.
 *
 * ## The report and the case are the same decision
 *
 * The report says the outcome on one line and the reason on the next, and the CASE says what
 * that verdict means to a consumer that branches on the exit code. A function that returned a
 * boolean and let the caller decide the number would split those two: the caller would write
 * the number, and the number would be a second copy of what the taxonomy says.
 *
 * ## Why an UNSTABLE run exits 0
 *
 * An unstable run did not fail: it finished, and what it finished with is a verdict the
 * operator reads on the line above. The taxonomy has no case for "unstable", and it cannot grow
 * one here: the eight cases are a published contract, and adding a ninth is a versioned break
 * that only the product can authorize. The case therefore says what the CODE means, and the
 * word "unstable" stays in the report — which is where the operator reads it.
 *
 * @return the case that owns this outcome's exit.
 */
internal fun reportRunOutcome(outcome: RunOutcome): CliExit = when (outcome) {
    is RunOutcome.Success -> {
        System.err.println("Pipeline finished with SUCCESS")
        CliExit.SUCCESS
    }
    is RunOutcome.Unstable -> {
        System.err.println("Pipeline finished with UNSTABLE")
        CliExit.Success(message = "the pipeline finished with an unstable verdict")
    }
    is RunOutcome.Failure -> {
        System.err.println("Pipeline finished with FAILURE")
        reportRunFailure(outcome.failure)
        CliExit.PipelineFailed(message = outcome.failure.message)
    }
    is RunOutcome.Aborted -> {
        System.err.println("Pipeline finished with FAILURE")
        System.err.println("  cause [ABORTED]: the run was cancelled or interrupted before a terminal outcome")
        CliExit.PipelineFailed(message = "the run was cancelled or interrupted before a terminal outcome")
    }
}

/**
 * The reason a run failed, in the form an operator can act on.
 *
 * ## Why the kind is on the line and not in the message
 *
 * The failure kinds are already typed ([dev.rubentxu.pipeline.v2.domain.FailureKind]), and
 * re-labelling them inside the message would create a second vocabulary. The label is therefore
 * the kind itself, spelled once, and the message is the message: `cause [USER]: …` says what
 * happened AND of which kind, and a report that carried only the message would leave the operator
 * guessing whether a missing permission, a bad script or a cancelled run produced it.
 *
 * ## Why stderr and not the event stream
 *
 * The `RunFinished` event is a lifecycle bookend and its shape is a published contract; widening
 * it is a separate decision. stderr is where the outcome is already summarised one line above,
 * and where every other operator-facing diagnostic goes. A message that is emitted nowhere is a
 * message that does not exist.
 */
internal fun reportRunFailure(failure: PipelineFailure) {
    System.err.println("  cause [${failure.kind}]: ${failure.message}")
    failure.cause?.let { System.err.println("  origin: $it") }
}
