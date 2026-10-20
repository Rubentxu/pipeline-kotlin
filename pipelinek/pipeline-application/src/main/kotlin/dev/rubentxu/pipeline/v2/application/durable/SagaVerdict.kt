package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.events.SagaTerminal

/**
 * What the run's verdict says about the Sagas it ran.
 *
 * ## The defect this exists to end
 *
 * A Saga that ended in `NEEDS_INTERVENTION` and a Saga that was fully compensated
 * produced the SAME stderr: the run failed, and the failure named the action that
 * failed. Nothing said that the first one left work for a human and the second one
 * did not.
 *
 * That silence is not cosmetic. "The Saga settled" and "a human has to resolve a
 * pending obligation right now" call for opposite responses from whoever reads the
 * run, and a run that cannot tell them apart is a run that cannot be triaged.
 *
 * ## Why the EVENT and not the store
 *
 * The terminal state is already a published fact: [SagaTerminal] is in the event
 * plane, it is durable, and it is what a machine consumer reads. Reading the
 * obligation store instead would be a second authority for the same fact, and it
 * would answer a different question — the store says what is OWED, the event says
 * what the engine CONCLUDED. The verdict reports the conclusion.
 *
 * ## What this does NOT do
 *
 * It decides nothing. The engine decided; this says the decision out loud, in the
 * same block that already says why the run failed.
 */
object SagaVerdict {

    /**
     * The verdict lines for the Sagas that reached a terminal state, in the order they
     * settled.
     *
     * One line per Saga: which one, how it ended, and — when it did not settle — why.
     * A Saga that settled is worth saying too: it is the difference between "the
     * failure was undone" and "the failure was left standing", and the operator
     * cannot read that from the run's exit code.
     */
    fun verdicts(terminals: List<SagaTerminal>): List<String> = terminals.map { terminal ->
        buildString {
            append("  saga [").append(terminal.sagaId).append("]: ").append(terminal.state)
            terminal.reason?.let { append(" (").append(it).append(")") }
        }
    }
}
