package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome

/**
 * Turns a run outcome into what the operator reads, and into the exit decision.
 *
 * ## One authority, because two paths used to disagree
 *
 * A run reaches its outcome through one of two execution paths — with a durable journal, or
 * with volatile stores — and the CLI answers the same question in both. They used to answer it
 * DIFFERENTLY: the durable path printed the failure and then its cause, while the volatile path
 * printed the cause in the same line and never named the failure's kind. The same failing
 * pipeline therefore produced two different reports depending on whether the operator had a
 * database, and a suite that ran only one of them could not see that there was a choice to make.
 *
 * Both paths call this, so the report is decided once and cannot drift. What it says is the whole
 * of [reportRunFailure]'s contract: the outcome on one line, and the reason on the next, with the
 * failure's kind and its origin.
 *
 * @return whether the run failed, which is the exit decision.
 */
internal fun reportRunOutcome(outcome: RunOutcome): Boolean = when (outcome) {
    is RunOutcome.Success -> {
        System.err.println("Pipeline finished with SUCCESS"); false
    }
    is RunOutcome.Unstable -> {
        System.err.println("Pipeline finished with UNSTABLE"); false
    }
    is RunOutcome.Failure -> {
        System.err.println("Pipeline finished with FAILURE")
        reportRunFailure(outcome.failure)
        true
    }
    is RunOutcome.Aborted -> {
        System.err.println("Pipeline finished with FAILURE")
        System.err.println("  cause [ABORTED]: the run was cancelled or interrupted before a terminal outcome")
        true
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
